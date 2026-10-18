package purekafka.sbtplugin

import sbt._
import sbt.Keys._

/** `asyncApiGenerateSpec` invokes `purekafka.docs.AsyncApiSpecWriter`
  * in-process via a dedicated `URLClassLoader` (not `(Compile / runMain)` or a
  * `java` subprocess — see its own comment below for why); `asyncApiGenerate`
  * renders static HTML from that spec, chained to run automatically after
  * `Compile / compile`.
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
      // don't depend on this project's own compile task, breaking the cycle.
      //
      // Also not a separate `java` subprocess: spawning a new OS process per
      // invocation added classpath-string/java.home plumbing for no benefit
      // once the real bug below was found and fixed (a missing-sequencing
      // issue, not a filesystem one - an external process would have hit the
      // exact same stale-classes symptom). Loading the target class
      // in-process via a dedicated URLClassLoader is simpler and avoids the
      // subprocess-spawn overhead on every compile.
      val classpath =
        (Compile / classDirectory).value +: Attributed.data(
          (Compile / dependencyClasspath).value
        )
      // `parent = null`, not this plugin's own classloader: confirmed
      // directly that parent-first delegation lets the sbt plugin's own
      // Scala 2.12 `scala.*` classes shadow the consuming project's Scala 3
      // ones (NoSuchMethodError on scala.Array$.unapplySeq - the two
      // binary-incompatible versions of the same class name). The target
      // project's own dependencyClasspath already carries its complete
      // Scala 3 runtime, so full isolation (rooted at the bootstrap
      // classloader only) is both necessary and sufficient.
      val loader =
        new java.net.URLClassLoader(classpath.map(_.toURI.toURL).toArray, null)
      try {
        val writerClass = loader.loadClass("purekafka.docs.AsyncApiSpecWriter$")
        val writerModule = writerClass.getField("MODULE$").get(null)
        val mainMethod =
          writerClass.getMethod("main", classOf[Array[String]])
        mainMethod.invoke(
          writerModule,
          Array(channelsClass, title, docVersion, outFile.getAbsolutePath)
        )
      } finally loader.close()
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
    // Def.taskDyn, not a plain task: confirmed directly that a plain task
    // body (`{ val a = (Compile/compile).value; asyncApiGenerate.value; a }`)
    // does NOT sequence asyncApiGenerate strictly after compile - sbt's task
    // macro gathers every `.value` call in a block as a sibling input to the
    // task, not as an imperative script, so asyncApiGenerate was starting
    // before the real compiler run had written any class files (confirmed via
    // a direct File.exists() check logged mid-task: false, logged *before*
    // "compiling N Scala sources" even printed). Wrapping the chained call in
    // a nested Def.task, only constructed once the outer taskDyn block's own
    // `.value` (compile) has resolved, is what actually enforces "compile,
    // then generate" ordering - the same idiom asyncApiGenerateSpec's own
    // (now superseded) runMain-based first draft used for a related reason.
    Compile / compile := Def.taskDyn {
      val analysis = (Compile / compile).value
      Def.task {
        asyncApiGenerate.value
        analysis
      }
    }.value
  )
}
