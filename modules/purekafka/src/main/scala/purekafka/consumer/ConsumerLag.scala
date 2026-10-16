package purekafka.consumer

import cats.effect.Async
import cats.syntax.all._
import fs2.Stream
import fs2.kafka.KafkaConsumer
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.Meter

import scala.concurrent.duration.FiniteDuration

/** Config for [[ConsumerLag.fromConsumer]]. `groupId` is recorded as a gauge
  * attribute (fs2-kafka's `KafkaConsumer` doesn't expose its own group id back
  * to the caller, so it has to be supplied).
  */
final case class ConsumerLagConfig(
    pollInterval: FiniteDuration,
    groupId: String
)

/** Polls and republishes Kafka's own per-partition consumer-lag metric as an
  * otel4s gauge — "is this consumer dead or just slow," the one signal this
  * module's resilience/supervision combinators can't give on their own (no HTTP
  * equivalent exists at all).
  *
  * Deliberately *not* computed by hand from `endOffsets`/`position` (fs2-kafka
  * 3.6.0's `KafkaOffsets`/`KafkaOffsetsV2` traits expose `position`/
  * `committed`, but no `endOffsets` at all) — Kafka's own Java client already
  * tracks this internally and exposes it via the standard `consumer.metrics()`
  * call fs2-kafka's `KafkaMetrics[F]` wraps directly. Confirmed via
  * `kafka-clients`' own `FetchMetricsRegistry`/`FetchMetricsManager` classes
  * (decompiled directly, not guessed): metric name `"records-lag"`, group
  * `"consumer-fetch-manager-metrics"`, tagged by `"topic"`/`"partition"` —
  * exactly the per-partition shape this needs, so reading it back is simpler
  * and more accurate than re-deriving it from raw offsets.
  */
object ConsumerLag {

  private val metricName = "records-lag"
  private val metricGroup = "consumer-fetch-manager-metrics"

  /** Core logic, parameterized over how the current per-partition lag values
    * are actually fetched — exposed (not private) so it's testable against a
    * synthetic `F[Map[(String, Int), Double]]` supplier with no real broker
    * involved, the same "test the logic, not the broker" convention this
    * module's other combinators ([[DeadLetter.fromPublish]]) already use.
    * [[fromConsumer]] below is the real fs2-kafka-backed constructor built on
    * top of this.
    */
  def record[F[_]: Async](
      config: ConsumerLagConfig
  )(
      meter: Meter[F]
  )(fetchLag: F[Map[(String, Int), Double]]): Stream[F, Unit] =
    Stream
      .eval(meter.gauge[Double]("purekafka.consumer.lag").create)
      .flatMap { gauge =>
        Stream.fixedDelay[F](config.pollInterval).evalMap { _ =>
          fetchLag.flatMap { lagByPartition =>
            lagByPartition.toList.traverse_ { case ((topic, partition), lag) =>
              gauge.record(
                lag,
                Attribute("messaging.destination.name", topic),
                Attribute(
                  "messaging.kafka.destination.partition",
                  partition.toString
                ),
                Attribute("messaging.consumer.group.name", config.groupId)
              )
            }
          }
        }
      }

  /** The real, fs2-kafka-backed lag gauge — polls `consumer.metrics()` on
    * `config.pollInterval` and records every `"records-lag"` value it finds.
    */
  def fromConsumer[F[_]: Async, K, V](
      config: ConsumerLagConfig
  )(
      meter: Meter[F]
  )(consumer: KafkaConsumer[F, K, V]): Stream[F, Unit] = {
    val fetchLag: F[Map[(String, Int), Double]] =
      consumer.metrics.map { metrics =>
        metrics.collect {
          case (name, metric)
              if name.name() == metricName && name.group() == metricGroup =>
            val tags = name.tags()
            val topic = tags.get("topic")
            val partition = tags.get("partition").toInt
            (topic, partition) -> metric
              .metricValue()
              .asInstanceOf[Number]
              .doubleValue()
        }
      }
    record(config)(meter)(fetchLag)
  }
}
