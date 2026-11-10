// SPDX-License-Identifier: MIT

package purekafka.sbtplugin

import sbt._
import sbt.util.Logger

import scala.sys.process._
import scala.util.control.NonFatal

/** Ports `scripts/generate-kafka-asyncapi-docs.sh`'s `@asyncapi/cli` +
  * `@asyncapi/html-template` logic to plain Scala process calls, for
  * `PureKafkaDocsPlugin`'s `asyncApiGenerate` task. Node-toolchain failures,
  * and a Node too old to run the generator, are warned here, never thrown — a
  * missing/broken Node/npm toolchain shouldn't fail a `Compile /
  * compile`-chained task.
  */
object AsyncApiHtmlGenerator {

  private val AsyncApiCliVersion = "6.2.2"
  private val HtmlTemplateVersion = "3.5.6"

  /** `@asyncapi/generator` 3.x's own `engines.node` (the CLI's is >= 24.0). */
  val MinimumNode: NodeVersion = NodeVersion(24, 11, 0)

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
  ): Unit =
    installedNode match {
      case Right(node) if node.atLeast(MinimumNode) =>
        render(specFile, outputDir, cliCacheDir, log)
      case found =>
        log.warn(
          s"asyncApiGenerate: AsyncAPI HTML needs Node >= $MinimumNode (${found.fold(identity, n => s"found $n")}); " +
            "skipping it, the spec JSON is still generated"
        )
    }

  /** The `node` on PATH, or why there isn't a usable one. */
  private def installedNode: Either[String, NodeVersion] = {
    val output =
      try Right(Seq("node", "-v").!!)
      catch {
        case _: java.io.IOException => Left("no node on PATH")
        // `!!` throws RuntimeException on a non-zero exit.
        case e: RuntimeException => Left(s"node -v failed: ${e.getMessage}")
      }
    output.flatMap(out =>
      NodeVersion
        .parse(out)
        .toRight(s"unrecognised node -v output: ${out.trim}")
    )
  }

  private def render(
      specFile: File,
      outputDir: File,
      cliCacheDir: File,
      log: Logger
  ): Unit = {
    // One directory per version pair, so a consumer's target/ cache from an
    // older pin (e.g. cli 4.1.1 + a patched html-template 1.0.0) is never
    // picked up after a plugin upgrade.
    val toolDir =
      cliCacheDir / s"cli-${AsyncApiCliVersion}_html-$HtmlTemplateVersion"
    val cliBin = toolDir / "node_modules" / ".bin" / "asyncapi"
    // Rendered from this local path, not the registry name: the generator
    // re-installs a registry-named template on every run (~2x slower and
    // needs the network on every compile).
    val htmlTemplate = toolDir / "node_modules" / "@asyncapi" / "html-template"

    if (!cliBin.exists() || !htmlTemplate.exists()) {
      log.info(
        s"asyncApiGenerate: installing @asyncapi/cli@$AsyncApiCliVersion and @asyncapi/html-template@$HtmlTemplateVersion into $toolDir (cached for next time)"
      )
      IO.createDirectory(toolDir)
      runChecked(
        Seq(
          "npm",
          "install",
          "--no-save",
          // sbt logs a subprocess's stderr as [error]: keep npm's deprecation
          // warnings out of the build log.
          "--loglevel=error",
          "--prefix",
          toolDir.getAbsolutePath,
          s"@asyncapi/cli@$AsyncApiCliVersion",
          s"@asyncapi/html-template@$HtmlTemplateVersion"
        ),
        log
      )
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
        htmlTemplate.getAbsolutePath,
        "--output",
        outputDir.getAbsolutePath,
        "--force-write",
        "--no-interactive"
      ),
      log
    )
  }

  private def runChecked(cmd: Seq[String], log: Logger): Unit = {
    // The cli's node-config otherwise warns, on stderr (logged as [error]),
    // that the consuming project has no config/ directory.
    val exitCode =
      Process(cmd, None, "SUPPRESS_NO_CONFIG_WARNING" -> "true").!(log)
    if (exitCode != 0)
      sys.error(
        s"command failed with exit code $exitCode: ${cmd.mkString(" ")}"
      )
  }
}
