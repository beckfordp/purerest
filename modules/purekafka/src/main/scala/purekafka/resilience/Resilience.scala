package purekafka.resilience

import cats.effect.Async
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.metrics.Meter

/** Config for [[Resilience.middleware]]: the retry and circuit-breaker settings
  * composed together. Same shape as `purerest.resilience.ResilienceConfig`.
  */
final case class ResilienceConfig(
    retry: RetryConfig,
    circuitBreaker: CircuitBreakerConfig
)

/** Composes [[Retry.middleware]] (outer) around [[CircuitBreaker.middleware]]
  * (inner), the same documented ordering `purerest.resilience.Resilience` uses
  * for its HTTP equivalent: each retry attempt passes through the circuit
  * breaker afresh, and a breaker-open rejection is *not* itself retried —
  * [[Retry]]'s `nonRetriable` hook is used here specifically to exclude
  * [[CircuitBreakerOpen]], so an open breaker fails fast on the very next
  * attempt rather than being retried until the retry budget is exhausted.
  */
object Resilience {

  /** The combined retry + circuit-breaker [[Guard]]. */
  def middleware[F[_]: Async](
      config: ResilienceConfig
  )(
      logger: StructuredLogger[F]
  )(meter: Meter[F]): Guard[F] = {
    val breakerGuard =
      CircuitBreaker.middleware[F](config.circuitBreaker)(meter)
    val retryGuard = Retry.middleware[F](config.retry)(logger)(
      meter,
      nonRetriable = _ == CircuitBreakerOpen
    )
    new Guard[F] {
      def apply[A](fa: F[A]): F[A] = retryGuard(breakerGuard(fa))
    }
  }
}
