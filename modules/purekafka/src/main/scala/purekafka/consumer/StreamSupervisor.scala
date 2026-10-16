package purekafka.consumer

import cats.effect.{Async, Ref}
import cats.syntax.all._
import fs2.Stream
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.metrics.{Counter, Meter}

import scala.concurrent.duration.FiniteDuration

/** Config for [[StreamSupervisor.supervise]]. `baseDelay` is doubled on each
  * successive restart (exponential backoff), capped at `maxDelay` so a
  * long-running outage doesn't grow the wait time unbounded. Unlike
  * `purekafka.resilience.RetryConfig`, there is deliberately no attempt limit
  * here — see [[StreamSupervisor]]'s own doc for why.
  */
final case class StreamSupervisorConfig(
    baseDelay: FiniteDuration,
    maxDelay: FiniteDuration
)

/** Restarts a long-running consumer `Stream[F, O]` with exponential backoff
  * when it errors, retrying *indefinitely* rather than giving up after N
  * attempts. Deliberately not the bounded `RetryConfig` shape
  * `purekafka.resilience.Retry` uses for a single call: giving up on a
  * background consumer after a fixed number of failures just recreates
  * `TECHNICAL_DEBT.md`'s TD-2 symptom (a permanently dead consumer fiber that
  * needs a manual pod restart) — a transient broker blip should always
  * eventually recover on its own instead. No circuit breaker either: there's no
  * "downstream being hammered" concept when the thing failing is *pulling* from
  * Kafka, not *sending* to a fragile dependency.
  *
  * The stream itself is taken as a by-name `F[Stream[F, O]]` build action, not
  * a plain `Stream[F, O]` value - restarting means rebuilding it from scratch
  * (e.g. re-subscribing a Kafka consumer), not resuming a half-consumed one.
  *
  * No existing fs2/Typelevel combinator does this directly: `Stream.retry`
  * retries a single effectful value, not a long-running stream, and
  * `handleErrorWith`/`repeat` alone don't give backoff or an attempt count to
  * log/meter - hence hand-rolled here, the same reasoning
  * `purekafka.resilience.Retry` already gives for not delegating to an existing
  * library for its own shape.
  */
object StreamSupervisor {

  /** Doubles `current`, capped at `config.maxDelay` - pure, so the cap and
    * growth behavior are testable without any real waiting.
    */
  def nextDelay(
      current: FiniteDuration,
      config: StreamSupervisorConfig
  ): FiniteDuration = (current * 2).min(config.maxDelay)

  /** The supervising stream described above. Constructed once per
    * [[StreamSupervisorConfig]]/logger/meter triple - its
    * `purekafka.stream.restarts` metric instrument is created once and reused
    * across every restart, same convention
    * `purekafka.resilience.Retry`/`CircuitBreaker` use for their own metrics.
    */
  def supervise[F[_]: Async, O](
      config: StreamSupervisorConfig
  )(
      logger: StructuredLogger[F]
  )(meter: Meter[F])(build: => F[Stream[F, O]]): Stream[F, O] = {
    val counterRef: Ref[F, Option[Counter[F, Long]]] = Ref.unsafe(None)

    def getCounter: F[Counter[F, Long]] =
      counterRef.get.flatMap {
        case Some(counter) => counter.pure[F]
        case None          =>
          meter
            .counter[Long]("purekafka.stream.restarts")
            .create
            .flatTap(counter => counterRef.set(Some(counter)))
      }

    def loop(delay: FiniteDuration, attempt: Int): Stream[F, O] =
      Stream.eval(build).flatten.handleErrorWith { error =>
        Stream.eval(
          logger.warn(error)(
            s"Consumer stream failed (attempt $attempt), restarting after $delay"
          ) *> getCounter.flatMap(_.inc())
        ) >>
          Stream.sleep[F](delay) >>
          loop(nextDelay(delay, config), attempt + 1)
      }

    loop(config.baseDelay, attempt = 1)
  }
}
