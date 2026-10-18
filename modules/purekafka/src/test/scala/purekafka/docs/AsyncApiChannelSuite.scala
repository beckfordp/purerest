package purekafka.docs

import munit.FunSuite
import sttp.tapir.Schema

class AsyncApiChannelSuite extends FunSuite {

  final case class DemoPayload(message: String)
  object DemoPayload {
    implicit val schema: Schema[DemoPayload] = Schema.derived[DemoPayload]
  }

  test(
    "exposes the topic, eventType, and implicit Schema it was constructed with"
  ) {
    val channel = AsyncApiChannel[DemoPayload](
      topic = "kafka-harness-demo",
      eventType = "DemoEvent"
    )

    assertEquals(channel.topic, "kafka-harness-demo")
    assertEquals(channel.eventType, "DemoEvent")
    assertEquals(channel.schema, DemoPayload.schema)
  }

  test(
    "direction defaults to ChannelDirection.Both(PublisherKind.Direct) when not specified"
  ) {
    val channel = AsyncApiChannel[DemoPayload](
      topic = "kafka-harness-demo",
      eventType = "DemoEvent"
    )

    assertEquals(
      channel.direction,
      ChannelDirection.Both(PublisherKind.Direct): ChannelDirection
    )
  }

  test(
    "direction can be set explicitly, e.g. Subscribe or Publish(Cdc)"
  ) {
    val subscribeOnly = AsyncApiChannel[DemoPayload](
      topic = "kafka-harness-demo",
      eventType = "DemoEvent",
      direction = ChannelDirection.Subscribe
    )
    val cdcPublish = AsyncApiChannel[DemoPayload](
      topic = "kafka-harness-demo",
      eventType = "DemoEvent",
      direction = ChannelDirection.Publish(PublisherKind.Cdc)
    )

    assertEquals(
      subscribeOnly.direction,
      ChannelDirection.Subscribe: ChannelDirection
    )
    assertEquals(
      cdcPublish.direction,
      ChannelDirection.Publish(PublisherKind.Cdc): ChannelDirection
    )
  }
}
