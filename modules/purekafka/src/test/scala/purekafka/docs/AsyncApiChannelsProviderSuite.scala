package purekafka.docs

import munit.FunSuite
import sttp.tapir.Schema

final case class FixturePayload(message: String)
object FixturePayload {
  implicit val schema: Schema[FixturePayload] = Schema.derived[FixturePayload]
}

object AsyncApiChannelsProviderFixture extends AsyncApiChannelsProvider {
  val channels: List[AsyncApiChannel[?]] = List(
    AsyncApiChannel[FixturePayload](
      topic = "fixture-topic",
      eventType = "FixtureEvent"
    )
  )
}

class AsyncApiChannelsProviderSuite extends FunSuite {

  test(
    "a fixture object implementing AsyncApiChannelsProvider is loaded reflectively by its class name and exposes channels correctly"
  ) {
    val className = "purekafka.docs.AsyncApiChannelsProviderFixture"
    val loaded = Class
      .forName(className + "$")
      .getField("MODULE$")
      .get(null)
      .asInstanceOf[AsyncApiChannelsProvider]

    assertEquals(loaded.channels, AsyncApiChannelsProviderFixture.channels)
  }
}
