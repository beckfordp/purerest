package kafkaharness

import purekafka.docs.{AsyncApiChannel, AsyncApiChannelsProvider}

/** This harness's registered topic/eventType/payload for AsyncAPI
  * documentation, as a singleton `AsyncApiChannelsProvider` — the shape the sbt
  * plugin's `AsyncApiSpecWriter` loads reflectively by class name (ADR 0010's
  * AsyncAPI follow-up, this track's sbt-plugin phase). Previously an inline
  * `val` inside `Main.run`; factored out so it has a stable,
  * reflectively-loadable class name independent of `Main`'s own runtime scope.
  */
object DemoChannels extends AsyncApiChannelsProvider {
  val channels: List[AsyncApiChannel[?]] = List(
    AsyncApiChannel[DemoEvent](
      topic = Main.topic,
      eventType = "DemoEvent"
    )
  )
}
