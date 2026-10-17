package purekafka.docs

import munit.FunSuite
import sttp.tapir.Schema

class AsyncApiChannelSuite extends FunSuite {

  final case class DemoPayload(message: String)
  object DemoPayload {
    implicit val schema: Schema[DemoPayload] = Schema.derived[DemoPayload]
  }

  test("exposes the topic, eventType, and implicit Schema it was constructed with") {
    val channel = AsyncApiChannel[DemoPayload](
      topic = "kafka-harness-demo",
      eventType = "DemoEvent"
    )

    assertEquals(channel.topic, "kafka-harness-demo")
    assertEquals(channel.eventType, "DemoEvent")
    assertEquals(channel.schema, DemoPayload.schema)
  }
}
