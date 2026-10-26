package purekafka.sbtplugin

import sbt._
import sbt.Keys._

import com.typesafe.sbt.SbtNativePackager.Universal
import com.typesafe.sbt.packager.universal.UniversalPlugin

/** `asyncApiGenerateSpec` invokes `purekafka.docs.AsyncApiSpecWriter`
  * in-process via a dedicated `URLClassLoader` (not `(Compile / runMain)` or a
  * `java` subprocess — see its own comment below for why); `asyncApiGenerate`
  * renders static HTML from that spec, chained to run automatically after
  * `Compile / compile`. `schemaRegistryCheck` invokes
  * `purekafka.docs.SchemaRegistryWriter` the same reflective way, but is
  * deliberately *not* chained after `Compile / compile` (Rework 5 step 2,
  * ADR 0003) — it makes a real network call to a schema registry, unlike
  * `asyncApiGenerate`'s pure local file writes, so local `sbt compile`/`sbt
  * test` must keep working even when no registry is running. Run it
  * explicitly (`sbt schemaRegistryCheck`), e.g. in CI.
  */
object PureKafkaDocsPlugin extends AutoPlugin {

  // Requires UniversalPlugin so native-packager's own `Universal / mappings`
  // setting is initialised before this plugin's `++=` append below (sbt
  // applies a plugin's settings after those of the plugins it requires).
  // `noTrigger` is essential: without it, sbt's default `allRequirements`
  // trigger would auto-activate this plugin on every JavaAppPackaging
  // project, including ones that never configure `asyncApiChannelsClass`.
  override def requires: Plugins = UniversalPlugin
  override def trigger: PluginTrigger = noTrigger

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
    val schemaRegistryUrl: SettingKey[String] = settingKey[String](
      "Base URL of the Apicurio Registry instance to check/register schemas against. Required to run schemaRegistryCheck."
    )
    val schemaRegistryGroupId: SettingKey[String] = settingKey[String](
      "Apicurio Registry group id schemas are registered under. Defaults to name.value (one group per service)."
    )
    val schemaRegistryCheck: TaskKey[Unit] = taskKey[Unit](
      "Registers/checks each AsyncApiChannel's JSON Schema against Apicurio Registry via purekafka.docs.SchemaRegistryWriter, failing the build on an incompatible change. Not chained after Compile/compile - run explicitly (e.g. in CI)."
    )
  }

  import autoImport._

  override def projectSettings: Seq[Setting[_]] = Seq(
    asyncApiTitle := name.value,
    asyncApiVersion := version.value,
    asyncApiOutputDir := target.value / "asyncapi-docs",
    schemaRegistryGroupId := name.value,
    schemaRegistryCheck := {
      // Unlike asyncApiGenerateSpec (only ever reached via Compile/compile's
      // own taskDyn chain below, so classes are already fresh by the time it
      // runs), this task is deliberately standalone - nothing else forces
      // compilation first. Confirmed directly: without this, `sbt
      // schemaRegistryCheck` on its own threw a bare ClassNotFoundException
      // for the (never-compiled) channels-provider class.
      val _ = (Compile / compile).value
      val channelsClass = asyncApiChannelsClass.value
      val registryUrl = schemaRegistryUrl.value
      val groupId = schemaRegistryGroupId.value
      // Same reflective-invocation mechanism asyncApiGenerateSpec uses
      // below (dedicated URLClassLoader, parent = null) - see its own
      // comments for exactly why (not (Compile/runMain), not a `java`
      // subprocess, Scala-version classloader isolation).
      val classpath =
        (Compile / classDirectory).value +: Attributed.data(
          (Compile / dependencyClasspath).value
        )
      val loader =
        new java.net.URLClassLoader(classpath.map(_.toURI.toURL).toArray, null)
      try {
        val writerClass =
          loader.loadClass("purekafka.docs.SchemaRegistryWriter$")
        val writerModule = writerClass.getField("MODULE$").get(null)
        val mainMethod =
          writerClass.getMethod("main", classOf[Array[String]])
        mainMethod.invoke(
          writerModule,
          Array(channelsClass, registryUrl, groupId)
        )
      } finally loader.close()
    },
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
    // Auto-wires the rendered HTML into sbt-native-packager images (TD-6):
    // without this, every consuming build had to repeat the same
    // `Universal / mappings ++=` block by hand. Inert for a project that
    // doesn't enable native-packager, and `asyncApiGenerate` only runs when a
    // packaging task actually reads `Universal / mappings`. The plugin stays
    // opt-in (`trigger = noTrigger` above) — enabling native-packager alone
    // must not activate it.
    Universal / mappings ++= {
      val base = asyncApiGenerate.value
      Path
        .allSubpaths(base)
        .map { case (file, relPath) =>
          file -> s"target/asyncapi-docs/$relPath"
        }
        .toSeq
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
