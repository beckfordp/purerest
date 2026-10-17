package purekafka.docs

import sttp.tapir.Schema

/** Describes one Kafka topic's publish/subscribe contract for AsyncAPI
  * documentation (ADR 0010's AsyncAPI follow-up) — the Kafka-side analogue of a
  * tapir `Endpoint`. Reuses tapir's own `Schema[A]` derivation for the payload
  * shape (already in scope via tapir, the same JSON-Schema-shaped output
  * gluon's ADR 0003 settled on) rather than inventing a second schema story.
  *
  * Generic, with no `kafka-harness`-specific (or any other service-specific)
  * coupling — a calling service registers its own channels; this type and the
  * document generation built on top of it (later phases of this track) stay
  * portable.
  */
final case class AsyncApiChannel[A](
    topic: String,
    eventType: String
)(implicit val schema: Schema[A])
