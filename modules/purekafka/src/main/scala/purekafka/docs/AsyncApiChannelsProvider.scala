package purekafka.docs

/** A consuming service's own list of registered [[AsyncApiChannel]]s, as a
  * singleton object implementing this trait — the shape the sbt plugin's
  * `AsyncApiSpecWriter` (next phase) loads reflectively by fully-qualified
  * class name, so `purekafka` never needs a compile-time dependency back onto
  * the consuming service.
  */
trait AsyncApiChannelsProvider {
  def channels: List[AsyncApiChannel[?]]
}
