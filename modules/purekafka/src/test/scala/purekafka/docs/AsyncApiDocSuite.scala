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

    val channelCursor = root.downField("channels").downField("kafka-harness-demo")
    assert(channelCursor.focus.isDefined, "expected a channel entry keyed by the topic name")

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
}
