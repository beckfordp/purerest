package kafkaharness

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import sttp.tapir.Schema

/** The payload `kafka-harness` produces/consumes on its `kafka-harness-demo`
  * topic — previously a raw `String`, upgraded here (ADR 0010's AsyncAPI
  * follow-up, Phase 4) so the registered `purekafka.docs.AsyncApiChannel` has a
  * non-trivial field to show in the generated document, instead of a bare
  * string payload.
  */
final case class DemoEvent(message: String)

object DemoEvent {
  implicit val codec: Codec[DemoEvent] = deriveCodec
  implicit val schema: Schema[DemoEvent] = Schema.derived[DemoEvent]
}
