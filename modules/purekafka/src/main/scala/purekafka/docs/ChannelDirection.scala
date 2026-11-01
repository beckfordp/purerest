package purekafka.docs

/** Whether a channel's publish side is a direct Kafka producer call or
  * delivered via Debezium CDC from an outbox table (ADR 0008 in gluon) — only
  * meaningful alongside [[ChannelDirection.Publish]]/
  * [[ChannelDirection.Both]], since a channel with no publish side has no
  * publisher to describe.
  */
sealed trait PublisherKind
object PublisherKind {
  case object Direct extends PublisherKind
  case object Cdc extends PublisherKind
}

/** Which operations an [[AsyncApiChannel]] contributes to in its topic's
  * generated AsyncAPI 3.0 document, from the service's own side: `Publish` →
  * `send`, `Subscribe` → `receive`, `Both` → both. `publisher` is only
  * expressible on the two variants that actually publish, so a `Subscribe`-only
  * channel can't carry meaningless publisher metadata.
  */
sealed trait ChannelDirection
object ChannelDirection {
  final case class Publish(publisher: PublisherKind) extends ChannelDirection
  case object Subscribe extends ChannelDirection
  final case class Both(publisher: PublisherKind) extends ChannelDirection
}
