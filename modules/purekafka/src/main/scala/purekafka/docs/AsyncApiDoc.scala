package purekafka.docs

import io.circe.Json
import io.circe.syntax._
import sttp.apispec.asyncapi.circe._
import sttp.tapir.docs.apispec.schema.TapirSchemaToJsonSchema

import scala.collection.immutable.ListMap

/** Builds an AsyncAPI 3.0.0 document (as circe `Json`) from a list of
  * registered [[AsyncApiChannel]]s — the Kafka-side analogue of
  * `purerest.docs.Docs.routes`'s tapir-endpoints-to-OpenAPI generation.
  *
  * 3.0, not 2.x: 2.x's `publish`/`subscribe` describe what a *client* does, so
  * a service's produced events sit under `publish` — the docs read as if the
  * service receives what it produces (gluon TD-19). 3.0's `send`/`receive`
  * actions are from the service's own side. sttp-apispec's `asyncapi-model`
  * only models 2.6.0 (checked up to 0.12.1), so the document is assembled as
  * plain circe `Json`; only the payload schemas still come from tapir's
  * `TapirSchemaToJsonSchema`, the same derivation `purerest.docs.Docs` uses for
  * REST.
  *
  * Layout: one channel per topic (`address` = topic) holding its messages
  * inline, keyed by `eventType`; one operation per action per topic,
  * referencing the channel and its messages. Each channel's
  * [[AsyncApiChannel.direction]] decides which operation(s) its message joins —
  * `Publish` → `send`, `Subscribe` → `receive`, `Both` → both. An operation is
  * omitted when no channel on the topic qualifies for it.
  */
object AsyncApiDoc {

  private val CdcDescription =
    "Published via Debezium CDC from an outbox table (ADR 0008 in gluon) - no direct producer call."

  private def sends(direction: ChannelDirection): Boolean =
    direction match {
      case ChannelDirection.Publish(_) | ChannelDirection.Both(_) => true
      case ChannelDirection.Subscribe                             => false
    }

  private def receives(direction: ChannelDirection): Boolean =
    direction match {
      case ChannelDirection.Subscribe | ChannelDirection.Both(_) => true
      case ChannelDirection.Publish(_)                           => false
    }

  private def isCdc(direction: ChannelDirection): Boolean =
    direction match {
      case ChannelDirection.Publish(PublisherKind.Cdc) |
          ChannelDirection.Both(PublisherKind.Cdc) =>
        true
      case _ => false
    }

  /** AsyncAPI 3.0 restricts channel/operation/message keys to
    * `^[A-Za-z0-9_\-]+$`; a topic's real name stays in the channel's `address`.
    */
  private def key(name: String): String =
    name.map(c => if (c.isLetterOrDigit || c == '_' || c == '-') c else '_')

  /** One inline message, named after the channel's `eventType`. `title` carries
    * the same value as `name`: html-template shows `title` above each message,
    * and this module has no separate human-friendly label. The CDC note is a
    * property of how the message is published, so a `Both(Cdc)` message carries
    * it for readers too.
    */
  private def toMessage(channel: AsyncApiChannel[?]): Json = {
    val payload =
      TapirSchemaToJsonSchema(channel.schema, markOptionsAsNullable = true)
    Json.obj(
      "name" -> channel.eventType.asJson,
      "title" -> channel.eventType.asJson,
      "payload" -> payload.asJson,
      "description" ->
        (if (isCdc(channel.direction)) CdcDescription.asJson else Json.Null)
    )
  }

  private def toOperation(
      action: String,
      channelKey: String,
      eventTypes: List[String]
  ): Json =
    Json.obj(
      "action" -> action.asJson,
      "channel" -> Json.obj("$ref" -> s"#/channels/$channelKey".asJson),
      "messages" -> eventTypes
        .map(eventType =>
          Json.obj(
            "$ref" -> s"#/channels/$channelKey/messages/${key(eventType)}".asJson
          )
        )
        .asJson
    )

  /** A topic's operations, keyed by the topic name — or `<topic>-send` /
    * `<topic>-receive` when the topic has both, since keys must be unique.
    */
  private def toOperations(
      channelKey: String,
      channelsForTopic: List[AsyncApiChannel[?]]
  ): List[(String, Json)] = {
    val sent = channelsForTopic.filter(c => sends(c.direction))
    val received = channelsForTopic.filter(c => receives(c.direction))
    val both = sent.nonEmpty && received.nonEmpty
    def opKey(action: String) = if (both) s"$channelKey-$action" else channelKey
    List("send" -> sent, "receive" -> received).collect {
      case (action, channels) if channels.nonEmpty =>
        opKey(action) -> toOperation(
          action,
          channelKey,
          channels.map(_.eventType)
        )
    }
  }

  /** Builds the full AsyncAPI document for `channels`, as circe `Json`. */
  def generate(
      title: String,
      version: String,
      channels: List[AsyncApiChannel[?]]
  ): Json = {
    val channelsByTopic =
      channels.foldLeft(ListMap.empty[String, List[AsyncApiChannel[?]]]) {
        (acc, channel) =>
          acc.updated(
            channel.topic,
            acc.getOrElse(channel.topic, Nil) :+ channel
          )
      }
    val channelEntries = channelsByTopic.toList.map {
      case (topic, channelsForTopic) =>
        key(topic) -> Json.obj(
          "address" -> topic.asJson,
          "messages" -> Json.fromFields(
            channelsForTopic.map(c => key(c.eventType) -> toMessage(c))
          )
        )
    }
    val operationEntries = channelsByTopic.toList.flatMap {
      case (topic, channelsForTopic) =>
        toOperations(key(topic), channelsForTopic)
    }
    // Unset fields are written as `null` above (and by the apispec schema
    // encoder) and dropped here: AsyncAPI's validator rejects `null` for
    // array/object-typed fields.
    Json
      .obj(
        "asyncapi" -> "3.0.0".asJson,
        "info" -> Json.obj(
          "title" -> title.asJson,
          "version" -> version.asJson
        ),
        "channels" -> Json.fromFields(channelEntries),
        "operations" -> Json.fromFields(operationEntries)
      )
      .deepDropNullValues
  }
}
