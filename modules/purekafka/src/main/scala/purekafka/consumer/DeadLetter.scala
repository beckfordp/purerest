package purekafka.consumer

import cats.effect.{Async, Resource}
import cats.syntax.all._
import fs2.kafka.{KafkaProducer, ProducerRecord, ProducerSettings}
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.metrics.Meter
import purekafka.resilience.{Resilience, ResilienceConfig, Retry, RetryConfig}

/** Processes a message with bounded retry (reusing
  * [[purekafka.resilience.Retry]]); if every retry is exhausted, publishes the
  * message's own key/value to `<topic>-dlq` instead of propagating the error,
  * and returns successfully — so a consumer `Stream` built on top of this can
  * carry on with the next message rather than being killed
  * ([[purekafka.consumer.StreamSupervisor]] would otherwise restart the whole
  * stream for one poison message) or stalling that partition. The DLQ publish
  * itself is also guarded, by [[purekafka.resilience.Resilience.middleware]]
  * (retry + circuit breaker) — a genuine single-call producer site, previously
  * unprotected. If that publish ultimately fails (its own retries and breaker
  * exhausted), `guard` now raises rather than swallowing — there's nothing more
  * to do locally at that point, and [[StreamSupervisor]]'s restart-with-backoff
  * is the correct fallback, same as any other stream failure.
  *
  * Deliberately reuses `Retry`'s bounded-retry-then-give-up shape for message
  * processing rather than hand-rolling a second one: the only genuinely new
  * behavior here is *what happens on exhaustion* (publish to the DLQ and
  * swallow, instead of raising the last error) — `RetryConfig`'s
  * `maxRetries`/`baseDelay` is the same bounded shape this needs, same
  * reasoning `Resilience` already gives for composing `Retry` rather than
  * reinventing retry policy a second time.
  */
trait DeadLetter[F[_], K, V] {

  /** Runs `process`, retrying per the configured [[RetryConfig]]; routes
    * `(key, value)` to `<topic>-dlq` and returns successfully if every retry is
    * exhausted, instead of raising - unless the DLQ publish itself then fails
    * (its own [[ResilienceConfig]] exhausted), in which case this raises.
    */
  def guard(topic: String, key: K, value: V)(process: F[Unit]): F[Unit]
}

object DeadLetter {

  /** Core logic, parameterized over how a dead-lettered message is actually
    * sent — exposed (not private) so it's testable against a fake `publish`
    * function with no real Kafka broker involved, the same "test the logic, not
    * the broker" convention this module's other combinators already use for
    * their own synthetic-`F[A]`/synthetic-`Stream` tests. [[resource]] below is
    * the real fs2-kafka-backed constructor built on top of this.
    */
  def fromPublish[F[_]: Async, K, V](
      config: RetryConfig,
      publishResilience: ResilienceConfig,
      publish: (String, K, V) => F[Unit]
  )(
      logger: StructuredLogger[F]
  )(meter: Meter[F]): F[DeadLetter[F, K, V]] = {
    val retryGuard = Retry.middleware[F](config)(logger)(meter)
    val publishGuard =
      Resilience.middleware[F](publishResilience)(logger)(meter)

    meter.counter[Long]("purekafka.dlq.published").create.map { counter =>
      new DeadLetter[F, K, V] {
        def guard(topic: String, key: K, value: V)(
            process: F[Unit]
        ): F[Unit] =
          retryGuard(process).handleErrorWith { error =>
            val dlqTopic = s"$topic-dlq"
            publishGuard(publish(dlqTopic, key, value)) *>
              counter.inc() *>
              logger.error(
                Map(
                  "topic" -> topic,
                  "dlq_topic" -> dlqTopic,
                  "key" -> key.toString
                ),
                error
              )(
                "Routed unprocessable message to DLQ after exhausting retries"
              )
          }
      }
    }
  }

  /** The real, fs2-kafka-backed [[DeadLetter]] — owns its own producer
    * internally (callers never construct or pass one in), matching the same
    * "self-contained, zero per-service forking" shape every other combinator in
    * this module already has.
    */
  def resource[F[_]: Async, K, V](
      config: RetryConfig,
      publishResilience: ResilienceConfig,
      producerSettings: ProducerSettings[F, K, V]
  )(
      logger: StructuredLogger[F]
  )(meter: Meter[F]): Resource[F, DeadLetter[F, K, V]] =
    KafkaProducer.resource(producerSettings).evalMap { producer =>
      fromPublish[F, K, V](
        config,
        publishResilience,
        (dlqTopic, key, value) =>
          producer
            .produceOne_(ProducerRecord(dlqTopic, key, value))
            .flatten
            .void
      )(logger)(meter)
    }
}
