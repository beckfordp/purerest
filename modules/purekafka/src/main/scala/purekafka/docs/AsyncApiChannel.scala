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
  * document generation built on top of it stay portable.
  *
  * `direction` defaults to `ChannelDirection.Both(PublisherKind.Direct)` —
  * every existing registration keeps compiling and generating the same output
  * unchanged. Set it explicitly once a channel's actual publish/subscribe role
  * (and, on the publish side, whether delivery is a direct producer call or
  * Debezium CDC from an outbox table — ADR 0008 in gluon) is known, so the
  * generated document only claims what's true.
  */
final case class AsyncApiChannel[A](
    topic: String,
    eventType: String,
    direction: ChannelDirection = ChannelDirection.Both(PublisherKind.Direct)
)(implicit val schema: Schema[A])
