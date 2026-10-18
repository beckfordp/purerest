package purekafka.sbtplugin

import sbt._
import sbt.Keys._

/** Settings/tasks land here incrementally through this phase; this step adds
  * just the settings keys, no tasks yet.
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
  }

  import autoImport._

  override def projectSettings: Seq[Setting[_]] = Seq(
    asyncApiTitle := name.value,
    asyncApiVersion := version.value,
    asyncApiOutputDir := target.value / "asyncapi-docs"
  )
}
