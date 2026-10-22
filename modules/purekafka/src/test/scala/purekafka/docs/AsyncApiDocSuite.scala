package purekafka.docs

import munit.FunSuite
import sttp.tapir.Schema

class AsyncApiDocSuite extends FunSuite {

  final case class DemoPayload(message: String)
  object DemoPayload {
    implicit val schema: Schema[DemoPayload] = Schema.derived[DemoPayload]
  }

  final case class OtherPayload(count: Int)
  object OtherPayload {
    implicit val schema: Schema[OtherPayload] = Schema.derived[OtherPayload]
  }

  test(
    "a single registered channel produces a valid AsyncAPI 2.x document with the channel name, a publish/subscribe operation, and a JSON-Schema-shaped payload"
  ) {
    val channel = AsyncApiChannel[DemoPayload](
      topic = "kafka-harness-demo",
      eventType = "DemoEvent"
    )

    val doc = AsyncApiDoc.generate("purekafka demo", "1.0", List(channel))
    val root = doc.hcursor

    val asyncapiVersion = root.downField("asyncapi").as[String].toOption
    assertEquals(asyncapiVersion, Some("2.6.0"))

    val title = root.downField("info").downField("title").as[String].toOption
    assertEquals(title, Some("purekafka demo"))

    val channelCursor =
      root.downField("channels").downField("kafka-harness-demo")
    assert(
      channelCursor.focus.isDefined,
      "expected a channel entry keyed by the topic name"
    )

    val publishName = channelCursor
      .downField("publish")
      .downField("message")
      .downField("name")
      .as[String]
      .toOption
    assertEquals(publishName, Some("DemoEvent"))

    val payloadFieldType = channelCursor
      .downField("publish")
      .downField("message")
      .downField("payload")
      .downField("properties")
      .downField("message")
      .downField("type")
      .as[String]
      .toOption
    assertEquals(
      payloadFieldType,
      Some("string"),
      "expected the payload's JSON Schema to reflect DemoPayload's `message: String` field"
    )

    val subscribeName = channelCursor
      .downField("subscribe")
      .downField("message")
      .downField("name")
      .as[String]
      .toOption
    assertEquals(
      subscribeName,
      Some("DemoEvent"),
      "both publish and subscribe should describe the same channel contract"
    )
  }

  test("multiple registered channels each appear as distinct entries") {
    val demo = AsyncApiChannel[DemoPayload](
      topic = "kafka-harness-demo",
      eventType = "DemoEvent"
    )
    val other = AsyncApiChannel[OtherPayload](
      topic = "kafka-harness-other",
      eventType = "OtherEvent"
    )

    val doc = AsyncApiDoc.generate("purekafka demo", "1.0", List(demo, other))
    val channelsCursor = doc.hcursor.downField("channels")

    assert(
      channelsCursor.downField("kafka-harness-demo").focus.isDefined,
      "expected the first channel's own entry"
    )
    assert(
      channelsCursor.downField("kafka-harness-other").focus.isDefined,
      "expected the second channel's own entry"
    )

    val otherEventName = channelsCursor
      .downField("kafka-harness-other")
      .downField("publish")
      .downField("message")
      .downField("name")
      .as[String]
      .toOption
    assertEquals(otherEventName, Some("OtherEvent"))
  }

  test(
    "two channels registered on the same topic with different eventTypes both appear, as a oneOf, neither dropped"
  ) {
    val demo = AsyncApiChannel[DemoPayload](
      topic = "shared-topic",
      eventType = "DemoEvent"
    )
    val other = AsyncApiChannel[OtherPayload](
      topic = "shared-topic",
      eventType = "OtherEvent"
    )

    val doc =
      AsyncApiDoc.generate("purekafka demo", "1.0", List(demo, other))
    val channelCursor =
      doc.hcursor.downField("channels").downField("shared-topic")

    val publishMessageNames = channelCursor
      .downField("publish")
      .downField("message")
      .downField("oneOf")
      .values
      .toList
      .flatten
      .flatMap(_.hcursor.downField("name").as[String].toOption)
    assertEquals(
      publishMessageNames.toSet,
      Set("DemoEvent", "OtherEvent"),
      "expected both eventTypes registered on the shared topic to appear, not just the last one"
    )

    val subscribeMessageNames = channelCursor
      .downField("subscribe")
      .downField("message")
      .downField("oneOf")
      .values
      .toList
      .flatten
      .flatMap(_.hcursor.downField("name").as[String].toOption)
    assertEquals(subscribeMessageNames.toSet, Set("DemoEvent", "OtherEvent"))
  }

  test(
    "a Subscribe-direction channel produces no publish operation for its topic"
  ) {
    val channel = AsyncApiChannel[DemoPayload](
      topic = "consumer-only-topic",
      eventType = "DemoEvent",
      direction = ChannelDirection.Subscribe
    )

    val doc = AsyncApiDoc.generate("purekafka demo", "1.0", List(channel))
    val channelCursor =
      doc.hcursor.downField("channels").downField("consumer-only-topic")

    assert(
      channelCursor.downField("publish").failed,
      "expected no publish operation for a Subscribe-only channel"
    )
    val subscribeName = channelCursor
      .downField("subscribe")
      .downField("message")
      .downField("name")
      .as[String]
      .toOption
    assertEquals(subscribeName, Some("DemoEvent"))
  }

  test(
    "a Publish-direction channel produces no subscribe operation for its topic"
  ) {
    val channel = AsyncApiChannel[DemoPayload](
      topic = "publisher-only-topic",
      eventType = "DemoEvent",
      direction = ChannelDirection.Publish(PublisherKind.Direct)
    )

    val doc = AsyncApiDoc.generate("purekafka demo", "1.0", List(channel))
    val channelCursor =
      doc.hcursor.downField("channels").downField("publisher-only-topic")

    assert(
      channelCursor.downField("subscribe").failed,
      "expected no subscribe operation for a Publish-only channel"
    )
    val publishName = channelCursor
      .downField("publish")
      .downField("message")
      .downField("name")
      .as[String]
      .toOption
    assertEquals(publishName, Some("DemoEvent"))
  }

  test(
    "a topic combining a Subscribe-only channel and a separate Publish channel splits correctly into the right oneOf buckets"
  ) {
    val consumedOnly = AsyncApiChannel[DemoPayload](
      topic = "mixed-direction-topic",
      eventType = "ConsumedOnly",
      direction = ChannelDirection.Subscribe
    )
    val publishedOnly = AsyncApiChannel[OtherPayload](
      topic = "mixed-direction-topic",
      eventType = "PublishedOnly",
      direction = ChannelDirection.Publish(PublisherKind.Direct)
    )

    val doc = AsyncApiDoc.generate(
      "purekafka demo",
      "1.0",
      List(consumedOnly, publishedOnly)
    )
    val channelCursor =
      doc.hcursor.downField("channels").downField("mixed-direction-topic")

    val publishName = channelCursor
      .downField("publish")
      .downField("message")
      .downField("name")
      .as[String]
      .toOption
    assertEquals(
      publishName,
      Some("PublishedOnly"),
      "only the Publish-direction channel's event type should appear in the publish operation"
    )

    val subscribeName = channelCursor
      .downField("subscribe")
      .downField("message")
      .downField("name")
      .as[String]
      .toOption
    assertEquals(
      subscribeName,
      Some("ConsumedOnly"),
      "only the Subscribe-direction channel's event type should appear in the subscribe operation"
    )
  }

  test(
    "a Publish(Cdc) channel's message includes a description noting CDC/outbox delivery"
  ) {
    val channel = AsyncApiChannel[DemoPayload](
      topic = "cdc-topic",
      eventType = "DemoEvent",
      direction = ChannelDirection.Publish(PublisherKind.Cdc)
    )

    val doc = AsyncApiDoc.generate("purekafka demo", "1.0", List(channel))
    val description = doc.hcursor
      .downField("channels")
      .downField("cdc-topic")
      .downField("publish")
      .downField("message")
      .downField("description")
      .as[String]
      .toOption

    assert(
      description.exists(_.contains("CDC")),
      s"expected a CDC-noting description, got $description"
    )
  }

  test(
    "a Publish(Direct) channel's message has no CDC description"
  ) {
    val channel = AsyncApiChannel[DemoPayload](
      topic = "direct-topic",
      eventType = "DemoEvent",
      direction = ChannelDirection.Publish(PublisherKind.Direct)
    )

    val doc = AsyncApiDoc.generate("purekafka demo", "1.0", List(channel))
    val descriptionCursor = doc.hcursor
      .downField("channels")
      .downField("direct-topic")
      .downField("publish")
      .downField("message")
      .downField("description")

    assert(
      descriptionCursor.failed,
      "expected no description field for a directly-published channel"
    )
  }

  test(
    "a Both(Cdc) channel's publish message has the CDC description but its subscribe message does not"
  ) {
    val channel = AsyncApiChannel[DemoPayload](
      topic = "both-cdc-topic",
      eventType = "DemoEvent",
      direction = ChannelDirection.Both(PublisherKind.Cdc)
    )

    val doc = AsyncApiDoc.generate("purekafka demo", "1.0", List(channel))
    val channelCursor =
      doc.hcursor.downField("channels").downField("both-cdc-topic")

    val publishDescription = channelCursor
      .downField("publish")
      .downField("message")
      .downField("description")
      .as[String]
      .toOption
    assert(
      publishDescription.exists(_.contains("CDC")),
      s"expected the publish side to carry the CDC note, got $publishDescription"
    )

    val subscribeDescriptionCursor = channelCursor
      .downField("subscribe")
      .downField("message")
      .downField("description")
    assert(
      subscribeDescriptionCursor.failed,
      "expected the subscribe side not to claim a publish provenance it doesn't have"
    )
  }
}
