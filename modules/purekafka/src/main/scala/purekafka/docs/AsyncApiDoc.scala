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
  * Each channel is registered with *both* a `publish` and a `subscribe`
  * operation carrying the same message — [[AsyncApiChannel]] has no
  * producer/consumer direction of its own (by design, see its own doc-comment:
  * generic, no service-specific coupling), so this describes the full
  * bidirectional contract of the topic rather than guessing a single direction.
  */
object AsyncApiDoc {

  /** Converts one [[AsyncApiChannel]] into an AsyncAPI `ChannelItem`: a
    * `SingleMessage` named after the channel's `eventType`, with its payload
    * schema derived from the channel's own tapir `Schema[A]` via
    * `TapirSchemaToJsonSchema` — the same JSON-Schema-shaped derivation
    * `purerest.docs.Docs` already uses for REST, not a second schema story.
    */
  private def toChannelItem(channel: AsyncApiChannel[?]): ChannelItem = {
    val payloadSchema =
      TapirSchemaToJsonSchema(channel.schema, markOptionsAsNullable = true)
    val message = SingleMessage(
      name = Some(channel.eventType),
      payload = Some(Right(payloadSchema))
    )
    ChannelItem(
      publish = Some(Operation.inlineMessage(message)),
      subscribe = Some(Operation.inlineMessage(message))
    )
  }

  /** Builds the full AsyncAPI document for `channels`, as circe `Json`. */
  def generate(
      title: String,
      version: String,
      channels: List[AsyncApiChannel[?]]
  ): Json = {
    val channelEntries: ListMap[String, ReferenceOr[ChannelItem]] =
      ListMap.from(
        channels.map(channel => channel.topic -> Right(toChannelItem(channel)))
      )
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
