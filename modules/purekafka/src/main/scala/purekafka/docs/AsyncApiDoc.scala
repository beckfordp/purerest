package purekafka.docs

import io.circe.Json
import io.circe.syntax._
import sttp.apispec.asyncapi._
import sttp.apispec.asyncapi.circe._
import sttp.tapir.docs.apispec.schema.TapirSchemaToJsonSchema

import scala.collection.immutable.ListMap

/** Builds an AsyncAPI 2.6.0 document (as circe `Json`) from a list of
  * registered [[AsyncApiChannel]]s — the Kafka-side analogue of
  * `purerest.docs.Docs.routes`'s tapir-endpoints-to-OpenAPI generation.
  *
  * Deliberately *not* hand-rolled circe `Json` construction, despite this
  * track's own spec.md assuming "no good library fit exists" for AsyncAPI
  * generation on the JVM: found while implementing this phase that
  * `tapir-apispec-docs` (already a `purekafka` dependency, for
  * [[sttp.tapir.docs.apispec.schema.TapirSchemaToJsonSchema]]) transitively
  * depends on `com.softwaremill.sttp.apispec:asyncapi-model` — the same library
  * family tapir's own OpenAPI generation is built on already ships a real,
  * typed AsyncAPI 2.6.0 document model (`sttp.apispec.asyncapi.AsyncAPI` et
  * al.) plus circe codecs (`asyncapi-circe`, pinned to the exact version
  * `tapir-apispec-docs` itself resolves). Building against that typed model
  * instead of hand-assembling JSON removes an entire class of document-shape
  * bugs this module would otherwise have to get (and keep) right by hand.
  *
  * Each channel's [[AsyncApiChannel.direction]] decides which of a topic's
  * `publish`/`subscribe` operations it contributes a message to — a
  * `Subscribe`-only channel never appears under `publish`, and vice versa (ADR
  * 0010's AsyncAPI follow-up, direction-aware generation track). An operation
  * is omitted entirely from a `ChannelItem` (not an empty `oneOf`) when no
  * registered channel on that topic qualifies for it.
  */
object AsyncApiDoc {

  private val CdcDescription =
    "Published via Debezium CDC from an outbox table (ADR 0008 in gluon) - no direct producer call."

  private def includesPublish(direction: ChannelDirection): Boolean =
    direction match {
      case ChannelDirection.Publish(_) | ChannelDirection.Both(_) => true
      case ChannelDirection.Subscribe                             => false
    }

  private def includesSubscribe(direction: ChannelDirection): Boolean =
    direction match {
      case ChannelDirection.Subscribe | ChannelDirection.Both(_) => true
      case ChannelDirection.Publish(_)                           => false
    }

  private def publisherOf(direction: ChannelDirection): Option[PublisherKind] =
    direction match {
      case ChannelDirection.Publish(publisher) => Some(publisher)
      case ChannelDirection.Both(publisher)    => Some(publisher)
      case ChannelDirection.Subscribe          => None
    }

  /** Converts one [[AsyncApiChannel]] into a `SingleMessage` named after the
    * channel's `eventType`, with its payload schema derived from the channel's
    * own tapir `Schema[A]` via `TapirSchemaToJsonSchema`, the same
    * JSON-Schema-shaped derivation `purerest.docs.Docs` already uses for REST,
    * not a second schema story. `withCdcNote` is set only when this message is
    * being placed in a *publish* operation for a channel whose publisher is
    * `Cdc` — the subscribe side of a `Both(Cdc)` channel never claims a publish
    * provenance it doesn't have.
    */
  private def toMessage(
      channel: AsyncApiChannel[?],
      withCdcNote: Boolean
  ): SingleMessage = {
    val payloadSchema =
      TapirSchemaToJsonSchema(channel.schema, markOptionsAsNullable = true)
    SingleMessage(
      name = Some(channel.eventType),
      payload = Some(Right(payloadSchema)),
      description = if (withCdcNote) Some(CdcDescription) else None
    )
  }

  private def toOperationMessage(
      messages: List[SingleMessage]
  ): Option[Message] =
    messages match {
      case Nil           => None
      case single :: Nil => Some(single)
      case many          => Some(OneOfMessage(oneOf = many))
    }

  /** Converts all [[AsyncApiChannel]]s registered on one topic into an AsyncAPI
    * `ChannelItem`, splitting them into the `publish`/`subscribe` operations
    * each is eligible for per its own `direction`.
    *
    * `toMessage` (and the `TapirSchemaToJsonSchema` derivation inside it) runs
    * at most once per channel here, not once per operation — a `Both`-direction
    * channel (the common case) is eligible for *both* `publish` and
    * `subscribe`, so its publish-side message (built once, with the CDC note if
    * applicable) is reused directly as its subscribe-side message, stripping
    * the note via a cheap `.copy` rather than re-deriving the schema a second
    * time when one needs to differ from the other.
    */
  private def toChannelItem(
      channelsForTopic: List[AsyncApiChannel[?]]
  ): ChannelItem = {
    val messagesByChannel = channelsForTopic.map { channel =>
      val withCdcNote =
        publisherOf(channel.direction).contains(PublisherKind.Cdc)
      val publishMessage = toMessage(channel, withCdcNote)
      val subscribeMessage =
        if (withCdcNote) publishMessage.copy(description = None)
        else publishMessage
      (channel, publishMessage, subscribeMessage)
    }

    val publishMessage = toOperationMessage(messagesByChannel.collect {
      case (channel, message, _) if includesPublish(channel.direction) =>
        message
    })
    val subscribeMessage = toOperationMessage(messagesByChannel.collect {
      case (channel, _, message) if includesSubscribe(channel.direction) =>
        message
    })

    ChannelItem(
      publish = publishMessage.map(Operation.inlineMessage),
      subscribe = subscribeMessage.map(Operation.inlineMessage)
    )
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
    val channelEntries: ListMap[String, ReferenceOr[ChannelItem]] =
      channelsByTopic.map { case (topic, channelsForTopic) =>
        topic -> Right(toChannelItem(channelsForTopic))
      }
    val doc = AsyncAPI(
      info = Info(title = title, version = version),
      channels = channelEntries
    )
    // asyncapi-circe's derived Encoder serializes every unset Option field
    // as a literal JSON `null` rather than omitting the key - harmless to
    // circe itself, but AsyncAPI's own spec validator (used by
    // scripts/generate-kafka-asyncapi-docs.sh's `@asyncapi/cli` downstream)
    // rejects `null` for fields typed as array/object ("examples" property
    // type must be array", etc. - found while building Phase 5, a real
    // generation failure, not a false validator complaint). Found via
    // deepDropNullValues, circe's standard fix for exactly this.
    doc.asJson.deepDropNullValues
  }
}
