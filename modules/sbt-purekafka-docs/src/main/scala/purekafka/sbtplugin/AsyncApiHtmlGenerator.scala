package purekafka.sbtplugin

import sbt._
import sbt.util.Logger

import scala.sys.process._
import scala.util.control.NonFatal

/** Ports `scripts/generate-kafka-asyncapi-docs.sh`'s `@asyncapi/cli` + patched
  * `@asyncapi/html-template` logic to plain Scala process calls, for
  * `PureKafkaDocsPlugin`'s `asyncApiGenerate` task. Node-toolchain failures are
  * caught and warned here, never thrown — a missing/broken Node/npm toolchain
  * shouldn't fail a `Compile / compile`-chained task.
  */
object AsyncApiHtmlGenerator {

  private val AsyncApiCliVersion = "4.1.1"
  private val HtmlTemplateVersion = "1.0.0"

  def generate(
      specFile: File,
      outputDir: File,
      cliCacheDir: File,
      log: Logger
  ): Unit =
    try generateUnsafe(specFile, outputDir, cliCacheDir, log)
    catch {
      case NonFatal(e) =>
        log.warn(
          s"asyncApiGenerate: Node toolchain failure generating static HTML, leaving output as-is: ${e.getMessage}"
        )
    }

  private def generateUnsafe(
      specFile: File,
      outputDir: File,
      cliCacheDir: File,
      log: Logger
  ): Unit = {
    val cliBin = cliCacheDir / "node_modules" / ".bin" / "asyncapi"
    if (!cliBin.exists()) {
      log.info(
        s"asyncApiGenerate: installing @asyncapi/cli@$AsyncApiCliVersion into $cliCacheDir (cached for next time)"
      )
      IO.createDirectory(cliCacheDir)
      runChecked(
        Seq(
          "npm",
          "install",
          "--no-save",
          "--prefix",
          cliCacheDir.getAbsolutePath,
          s"@asyncapi/cli@$AsyncApiCliVersion"
        ),
        log
      )
    }

    // @asyncapi/html-template@1.0.0's package.json declares
    // generator.apiVersion "v2", which a plain @asyncapi/parser install
    // (ParserAPIVersion 3) silently renders as an empty document (no error -
    // see the shell script's own doc comment for the full diagnosis). Patch
    // a local copy's apiVersion to "v3" once, cached alongside the cli
    // install, and always generate from that local path - @asyncapi/
    // generator skips its "reinstall every run" behavior for local paths.
    val generatorNodeModules =
      cliCacheDir / "node_modules" / "@asyncapi" / "generator" / "node_modules"
    val htmlTemplateOriginal =
      generatorNodeModules / "@asyncapi" / "html-template"
    val htmlTemplatePatched =
      generatorNodeModules / "@asyncapi" / "html-template-patched"

    if (!htmlTemplatePatched.exists()) {
      if (!htmlTemplateOriginal.exists()) {
        log.info(
          s"asyncApiGenerate: installing @asyncapi/html-template@$HtmlTemplateVersion once, to patch it"
        )
        val scratchDir = IO.createTemporaryDirectory
        // Best-effort: this first render is discarded (it has the
        // un-patched apiVersion bug) - only its side effect, installing
        // html-template into generatorNodeModules, is wanted. Swallow its
        // failure the same way the shell script does (`|| true`).
        try
          runChecked(
            Seq(
              cliBin.getAbsolutePath,
              "generate",
              "fromTemplate",
              specFile.getAbsolutePath,
              s"@asyncapi/html-template@$HtmlTemplateVersion",
              "--output",
              scratchDir.getAbsolutePath,
              "--force-write"
            ),
            log
          )
        catch { case NonFatal(_) => () }
      }
      IO.copyDirectory(htmlTemplateOriginal, htmlTemplatePatched)
      patchApiVersion(htmlTemplatePatched / "package.json")
    }

    // Clear output dir contents without removing the directory itself - a
    // live bind mount (e.g. kafka-harness's docker-compose observability
    // profile) loses track of a deleted-and-recreated mount point.
    IO.createDirectory(outputDir)
    IO.delete(IO.listFiles(outputDir).toList)

    runChecked(
      Seq(
        cliBin.getAbsolutePath,
        "generate",
        "fromTemplate",
        specFile.getAbsolutePath,
        htmlTemplatePatched.getAbsolutePath,
        "--output",
        outputDir.getAbsolutePath,
        "--force-write"
      ),
      log
    )
  }

  private def patchApiVersion(packageJson: File): Unit = {
    val content = IO.read(packageJson)
    val patched =
      content.replaceFirst(
        "\"apiVersion\"\\s*:\\s*\"v2\"",
        "\"apiVersion\": \"v3\""
      )
    IO.write(packageJson, patched)
  }

  private def runChecked(cmd: Seq[String], log: Logger): Unit = {
    val exitCode = Process(cmd).!(log)
    if (exitCode != 0)
      sys.error(
        s"command failed with exit code $exitCode: ${cmd.mkString(" ")}"
      )
  }
}
