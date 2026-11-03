package purekafka.consumer

import cats.effect.Clock
import cats.syntax.all._
import cats.Monad
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{BucketBoundaries, Histogram, Meter}

/** Event age at consume: now − the Kafka record timestamp, as histogram
  * `purekafka.consumer.event.age` (seconds) — gluon PERF-4.2. Offset lag
  * ([[ConsumerLag]]) says how many records are queued; this says how late the
  * one being handled is, which is what an end-to-end latency question needs.
  */
trait EventAge[F[_]] {

  /** Records the age of a record with `recordTimestampMillis` (Kafka's
    * `CreateTime`), clamped at zero for clock skew.
    */
  def record(
      topic: String,
      groupId: String,
      recordTimestampMillis: Long
  ): F[Unit]
}

object EventAge {

  /** 10ms–10min: covers a healthy hop (tens of ms) up to a backlog draining
    * after a spike. Wider than purerestlib's request-latency buckets.
    */
  val boundaries: BucketBoundaries = BucketBoundaries(
    0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0, 120.0,
    300.0, 600.0
  )

  def create[F[_]: Monad: Clock](meter: Meter[F]): F[EventAge[F]] =
    meter
      .histogram[Double]("purekafka.consumer.event.age")
      .withUnit("s")
      .withDescription("Age of a Kafka record when consumed (now - timestamp).")
      .withExplicitBucketBoundaries(boundaries)
      .create
      .map(fromHistogram[F])

  private def fromHistogram[F[_]: Monad: Clock](
      histogram: Histogram[F, Double]
  ): EventAge[F] =
    (topic, groupId, recordTimestampMillis) =>
      Clock[F].realTime.flatMap { now =>
        histogram.record(
          math.max(0L, now.toMillis - recordTimestampMillis) / 1000.0,
          Attribute("messaging.destination.name", topic),
          Attribute("messaging.consumer.group.name", groupId)
        )
      }
}
