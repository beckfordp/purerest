package purekafka.sbtplugin

import sbt._
import sbt.Keys._

import scala.sys.process._

/** `asyncApiGenerateSpec` invokes `purekafka.docs.AsyncApiSpecWriter` via a
  * direct `java` subprocess (not `(Compile / runMain)` — see its own comment
  * below for why); `asyncApiGenerate` renders static HTML from that spec,
  * chained to run automatically after `Compile / compile`.
  */
object PureKafkaDocsPlugin extends AutoPlugin {

  object autoImport {
    val asyncApiChannelsClass: SettingKey[String] = settingKey[String](
      "Fully-qualified class name of the project's AsyncApiChannelsProvider singleton. Required."
    )
    val asyncApiTitle: SettingKey[String] = settingKey[String](
      "Title for the generated AsyncAPI document. Defaults to name.value."
    )
    val asyncApiVersion: SettingKey[String] = settingKey[String](
      "Version for the generated AsyncAPI document. Defaults to version.value."
    )
    val asyncApiOutputDir: SettingKey[File] = settingKey[File](
      "Directory generated AsyncAPI docs are written to. Defaults to target/asyncapi-docs."
    )
    val asyncApiGenerateSpec: TaskKey[File] = taskKey[File](
      "Generates the AsyncAPI JSON spec via purekafka.docs.AsyncApiSpecWriter, writing to target/asyncapi-docs-spec/asyncapi.json."
    )
    val asyncApiGenerate: TaskKey[File] = taskKey[File](
      "Renders static AsyncAPI HTML (via @asyncapi/cli + a patched html-template) from the generated spec, into asyncApiOutputDir. Node-toolchain failures warn and continue rather than failing the task."
    )
  }

  import autoImport._

  override def projectSettings: Seq[Setting[_]] = Seq(
    asyncApiTitle := name.value,
    asyncApiVersion := version.value,
    asyncApiOutputDir := target.value / "asyncapi-docs",
    asyncApiGenerateSpec := {
      val channelsClass = asyncApiChannelsClass.value
      val title = asyncApiTitle.value
      val docVersion = asyncApiVersion.value
      val outFile = target.value / "asyncapi-docs-spec" / "asyncapi.json"
      // Not (Compile / runMain): that task itself depends on (Compile /
      // compile), and this task is chained to run *after* (Compile /
      // compile) below - going through runMain here would make that chain
      // circular (confirmed directly: it hangs rather than failing fast,
      // since Def.taskDyn-based dependencies aren't checked by sbt's static
      // cyclic-reference detection). classDirectory/dependencyClasspath
      // don't depend on this project's own compile task, so a direct `java`
      // subprocess with that classpath breaks the cycle.
      val classpath =
        (Compile / classDirectory).value +: Attributed.data(
          (Compile / dependencyClasspath).value
        )
      val javaBin = file(sys.props("java.home")) / "bin" / "java"
      val cmd = Seq(
        javaBin.getAbsolutePath,
        "-cp",
        classpath.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator),
        "purekafka.docs.AsyncApiSpecWriter",
        channelsClass,
        title,
        docVersion,
        outFile.getAbsolutePath
      )
      val exitCode = Process(cmd).!(streams.value.log)
      if (exitCode != 0)
        sys.error(s"AsyncApiSpecWriter failed with exit code $exitCode")
      outFile
    },
    asyncApiGenerate := {
      val specFile = asyncApiGenerateSpec.value
      val outputDir = asyncApiOutputDir.value
      val cliCacheDir = target.value / "asyncapi-cli-tool"
      AsyncApiHtmlGenerator
        .generate(specFile, outputDir, cliCacheDir, streams.value.log)
      outputDir
    },
    Compile / compile := {
      val analysis = (Compile / compile).value
      asyncApiGenerate.value
      analysis
    }
  )
}
