package kafkaharness

import sttp.tapir.Schema

/** A purely illustrative payload shape for `DemoChannels`' CDC-labeled channel
  * (ADR 0010's AsyncAPI follow-up, CDC-direction documentation track) — stands
  * in for what an outbox row's `payload` column holds in a real Debezium CDC
  * setup (ADR 0008 in gluon): the already-assembled domain-event envelope, same
  * shape a service's own `AsyncApiChannel[A]` registration already uses for a
  * direct-published event. `kafka-harness` has no real outbox table or Debezium
  * connector — this type and its registration exist only to demonstrate what a
  * CDC-published channel's generated documentation looks like, never actually
  * produced or consumed by this app.
  */
final case class DemoOutboxEvent(orderId: String, status: String)

object DemoOutboxEvent {
  implicit val schema: Schema[DemoOutboxEvent] = Schema.derived[DemoOutboxEvent]
}
