package kafkaharness

import purekafka.docs.{
  AsyncApiChannel,
  AsyncApiChannelsProvider,
  ChannelDirection,
  PublisherKind
}

/** This harness's registered topic/eventType/payload for AsyncAPI
  * documentation, as a singleton `AsyncApiChannelsProvider` — the shape the sbt
  * plugin's `AsyncApiSpecWriter` loads reflectively by class name (ADR 0010's
  * AsyncAPI follow-up, this track's sbt-plugin phase). Previously an inline
  * `val` inside `Main.run`; factored out so it has a stable,
  * reflectively-loadable class name independent of `Main`'s own runtime scope.
  */
object DemoChannels extends AsyncApiChannelsProvider {
  val channels: List[AsyncApiChannel[?]] = List(
    // Real channel: kafka-harness both produces and consumes this topic
    // itself (see Main.scala), so Both(Direct) — the default, set here
    // explicitly for clarity — accurately reflects its actual behavior.
    AsyncApiChannel[DemoEvent](
      topic = Main.topic,
      eventType = "DemoEvent",
      direction = ChannelDirection.Both(PublisherKind.Direct)
    ),
    // Illustrative only: kafka-harness has no real outbox table or
    // Debezium connector. Demonstrates what a CDC-published channel's
    // generated documentation looks like (publish-only, with a note that
    // delivery is via Debezium CDC rather than a direct producer call —
    // see DemoOutboxEvent's own doc comment) — never actually produced or
    // consumed by this app.
    AsyncApiChannel[DemoOutboxEvent](
      topic = "kafka-harness-outbox-demo",
      eventType = "OrderStatusChanged",
      direction = ChannelDirection.Publish(PublisherKind.Cdc)
    )
  )
}
