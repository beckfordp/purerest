// SPDX-License-Identifier: MIT

package purekafka.docs

import io.circe.{ACursor, Json}
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

  private def generate(channels: AsyncApiChannel[?]*): Json =
    AsyncApiDoc.generate("purekafka demo", "1.0", channels.toList)

  private def operation(doc: Json, key: String): ACursor =
    doc.hcursor.downField("operations").downField(key)

  private def action(doc: Json, key: String): Option[String] =
    operation(doc, key).downField("action").as[String].toOption

  /** The eventTypes an operation references, read from its `$ref`s. */
  private def operationMessages(doc: Json, key: String): List[String] =
    operation(doc, key)
      .downField("messages")
      .values
      .toList
      .flatten
      .flatMap(_.hcursor.downField("$ref").as[String].toOption)
      .map(_.split('/').last)

  private def message(doc: Json, channelKey: String, eventType: String) =
    doc.hcursor
      .downField("channels")
      .downField(channelKey)
      .downField("messages")
      .downField(eventType)

  test(
    "a single channel produces an AsyncAPI 3.0 document: channel addressed by topic, inline message with a JSON-Schema payload"
  ) {
    val doc = generate(
      AsyncApiChannel[DemoPayload](
        topic = "kafka-harness-demo",
        eventType = "DemoEvent",
        direction = ChannelDirection.Publish(PublisherKind.Direct)
      )
    )
    val root = doc.hcursor

    assertEquals(root.downField("asyncapi").as[String].toOption, Some("3.0.0"))
    assertEquals(
      root.downField("info").downField("title").as[String].toOption,
      Some("purekafka demo")
    )
    assertEquals(
      root
        .downField("channels")
        .downField("kafka-harness-demo")
        .downField("address")
        .as[String]
        .toOption,
      Some("kafka-harness-demo")
    )

    val demo = message(doc, "kafka-harness-demo", "DemoEvent")
    assertEquals(demo.downField("name").as[String].toOption, Some("DemoEvent"))
    assertEquals(demo.downField("title").as[String].toOption, Some("DemoEvent"))
    assertEquals(
      demo
        .downField("payload")
        .downField("properties")
        .downField("message")
        .downField("type")
        .as[String]
        .toOption,
      Some("string"),
      "expected the payload's JSON Schema to reflect DemoPayload's `message: String` field"
    )
  }

  test(
    "a Publish channel becomes a send operation keyed by the topic name, referencing its channel and message"
  ) {
    val doc = generate(
      AsyncApiChannel[DemoPayload](
        topic = "publisher-only-topic",
        eventType = "DemoEvent",
        direction = ChannelDirection.Publish(PublisherKind.Direct)
      )
    )

    assertEquals(action(doc, "publisher-only-topic"), Some("send"))
    assertEquals(
      operation(doc, "publisher-only-topic")
        .downField("channel")
        .downField("$ref")
        .as[String]
        .toOption,
      Some("#/channels/publisher-only-topic")
    )
    assertEquals(
      operation(doc, "publisher-only-topic")
        .downField("messages")
        .downN(0)
        .downField("$ref")
        .as[String]
        .toOption,
      Some("#/channels/publisher-only-topic/messages/DemoEvent")
    )
  }

  test(
    "a Subscribe channel becomes a receive operation keyed by the topic name"
  ) {
    val doc = generate(
      AsyncApiChannel[DemoPayload](
        topic = "consumer-only-topic",
        eventType = "DemoEvent",
        direction = ChannelDirection.Subscribe
      )
    )

    assertEquals(action(doc, "consumer-only-topic"), Some("receive"))
    assertEquals(
      operationMessages(doc, "consumer-only-topic"),
      List("DemoEvent")
    )
    assertEquals(
      doc.hcursor.downField("operations").keys.map(_.toList),
      Some(List("consumer-only-topic")),
      "expected no send operation for a Subscribe-only channel"
    )
  }

  test(
    "a Both channel gets <topic>-send and <topic>-receive operations, and no bare topic key"
  ) {
    val doc = generate(
      AsyncApiChannel[DemoPayload](
        topic = "both-topic",
        eventType = "DemoEvent"
      )
    )

    assertEquals(action(doc, "both-topic-send"), Some("send"))
    assertEquals(action(doc, "both-topic-receive"), Some("receive"))
    assert(operation(doc, "both-topic").failed)
    assertEquals(operationMessages(doc, "both-topic-send"), List("DemoEvent"))
    assertEquals(
      operationMessages(doc, "both-topic-receive"),
      List("DemoEvent")
    )
  }

  test("multiple topics each get their own channel and operation") {
    val doc = generate(
      AsyncApiChannel[DemoPayload](
        topic = "kafka-harness-demo",
        eventType = "DemoEvent",
        direction = ChannelDirection.Subscribe
      ),
      AsyncApiChannel[OtherPayload](
        topic = "kafka-harness-other",
        eventType = "OtherEvent",
        direction = ChannelDirection.Publish(PublisherKind.Direct)
      )
    )

    assertEquals(action(doc, "kafka-harness-demo"), Some("receive"))
    assertEquals(action(doc, "kafka-harness-other"), Some("send"))
    assertEquals(
      message(doc, "kafka-harness-other", "OtherEvent")
        .downField("name")
        .as[String]
        .toOption,
      Some("OtherEvent")
    )
  }

  test(
    "two eventTypes on one topic both appear on the channel and in its operation, neither dropped"
  ) {
    val doc = generate(
      AsyncApiChannel[DemoPayload](
        topic = "shared-topic",
        eventType = "DemoEvent",
        direction = ChannelDirection.Subscribe
      ),
      AsyncApiChannel[OtherPayload](
        topic = "shared-topic",
        eventType = "OtherEvent",
        direction = ChannelDirection.Subscribe
      )
    )

    assertEquals(
      doc.hcursor
        .downField("channels")
        .downField("shared-topic")
        .downField("messages")
        .keys
        .map(_.toSet),
      Some(Set("DemoEvent", "OtherEvent"))
    )
    assertEquals(
      operationMessages(doc, "shared-topic"),
      List("DemoEvent", "OtherEvent")
    )
  }

  test(
    "a topic mixing a Subscribe channel and a Publish channel splits them between receive and send"
  ) {
    val doc = generate(
      AsyncApiChannel[DemoPayload](
        topic = "mixed-direction-topic",
        eventType = "ConsumedOnly",
        direction = ChannelDirection.Subscribe
      ),
      AsyncApiChannel[OtherPayload](
        topic = "mixed-direction-topic",
        eventType = "PublishedOnly",
        direction = ChannelDirection.Publish(PublisherKind.Direct)
      )
    )

    assertEquals(
      operationMessages(doc, "mixed-direction-topic-send"),
      List("PublishedOnly")
    )
    assertEquals(
      operationMessages(doc, "mixed-direction-topic-receive"),
      List("ConsumedOnly")
    )
  }

  test(
    "topic characters outside the 3.0 key pattern are replaced in keys and refs, but kept in the address"
  ) {
    val doc = generate(
      AsyncApiChannel[DemoPayload](
        topic = "legacy.topic",
        eventType = "DemoEvent",
        direction = ChannelDirection.Subscribe
      )
    )

    assertEquals(
      doc.hcursor
        .downField("channels")
        .downField("legacy_topic")
        .downField("address")
        .as[String]
        .toOption,
      Some("legacy.topic")
    )
    assertEquals(
      operation(doc, "legacy_topic")
        .downField("channel")
        .downField("$ref")
        .as[String]
        .toOption,
      Some("#/channels/legacy_topic")
    )
  }

  test("a Publish(Cdc) channel's message notes CDC/outbox delivery") {
    val doc = generate(
      AsyncApiChannel[DemoPayload](
        topic = "cdc-topic",
        eventType = "DemoEvent",
        direction = ChannelDirection.Publish(PublisherKind.Cdc)
      )
    )
    val description = message(doc, "cdc-topic", "DemoEvent")
      .downField("description")
      .as[String]
      .toOption

    assert(
      description.exists(_.contains("CDC")),
      s"expected a CDC-noting description, got $description"
    )
  }

  test(
    "a Both(Cdc) message carries the CDC note once, referenced by both its send and receive operations"
  ) {
    val doc = generate(
      AsyncApiChannel[DemoPayload](
        topic = "both-cdc-topic",
        eventType = "DemoEvent",
        direction = ChannelDirection.Both(PublisherKind.Cdc)
      )
    )
    val description = message(doc, "both-cdc-topic", "DemoEvent")
      .downField("description")
      .as[String]
      .toOption

    assert(description.exists(_.contains("CDC")), s"got $description")
    assertEquals(
      operationMessages(doc, "both-cdc-topic-send"),
      List("DemoEvent")
    )
    assertEquals(
      operationMessages(doc, "both-cdc-topic-receive"),
      List("DemoEvent")
    )
  }

  test("Publish(Direct) and Subscribe messages have no CDC description") {
    val doc = generate(
      AsyncApiChannel[DemoPayload](
        topic = "direct-topic",
        eventType = "DemoEvent",
        direction = ChannelDirection.Publish(PublisherKind.Direct)
      ),
      AsyncApiChannel[DemoPayload](
        topic = "consumed-topic",
        eventType = "DemoEvent",
        direction = ChannelDirection.Subscribe
      )
    )

    assert(
      message(doc, "direct-topic", "DemoEvent").downField("description").failed
    )
    assert(
      message(doc, "consumed-topic", "DemoEvent")
        .downField("description")
        .failed
    )
  }

  test(
    "send operations come before receive operations, each in registration order, and channels follow the same order"
  ) {
    val doc = generate(
      AsyncApiChannel[DemoPayload](
        topic = "consumed-a",
        eventType = "A",
        direction = ChannelDirection.Subscribe
      ),
      AsyncApiChannel[DemoPayload](
        topic = "produced-b",
        eventType = "B",
        direction = ChannelDirection.Publish(PublisherKind.Direct)
      ),
      AsyncApiChannel[DemoPayload](
        topic = "consumed-c",
        eventType = "C",
        direction = ChannelDirection.Subscribe
      ),
      AsyncApiChannel[DemoPayload](
        topic = "both-d",
        eventType = "D",
        direction = ChannelDirection.Both(PublisherKind.Direct)
      ),
      AsyncApiChannel[DemoPayload](
        topic = "produced-e",
        eventType = "E",
        direction = ChannelDirection.Publish(PublisherKind.Direct)
      )
    )

    assertEquals(
      doc.hcursor.downField("operations").keys.map(_.toList),
      Some(
        List(
          "produced-b",
          "both-d-send",
          "produced-e",
          "consumed-a",
          "consumed-c",
          "both-d-receive"
        )
      )
    )
    assertEquals(
      doc.hcursor.downField("channels").keys.map(_.toList),
      Some(
        List("produced-b", "both-d", "produced-e", "consumed-a", "consumed-c")
      )
    )
  }

  test("the document contains no null values") {
    val doc = generate(
      AsyncApiChannel[DemoPayload](topic = "t", eventType = "DemoEvent")
    )
    def hasNull(json: Json): Boolean =
      json.isNull ||
        json.asArray.exists(_.exists(hasNull)) ||
        json.asObject.exists(_.values.exists(hasNull))
    assert(!hasNull(doc), doc.spaces2)
  }
}
