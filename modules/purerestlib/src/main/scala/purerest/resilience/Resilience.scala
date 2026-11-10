// SPDX-License-Identifier: MIT

package purerest.resilience

import cats.effect.Async
import org.http4s.client.Client
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.metrics.Meter

/** Config for [[Resilience.middleware]]: the retry and circuit-breaker settings
  * composed together.
  */
final case class ResilienceConfig(
    retry: RetryConfig,
    circuitBreaker: CircuitBreakerConfig
)

/** Composes [[Retry.middleware]] (outer) around [[CircuitBreaker.middleware]]
  * (inner) — resilience4j's own documented recommended ordering. Each retry
  * attempt passes through the circuit breaker afresh; a breaker-open rejection
  * is not itself retried ([[Retry]]'s retry-scope only covers connection
  * errors/timeouts/5xx, not [[CircuitBreakerOpen]]), so an open breaker fails
  * fast on the very next attempt rather than being retried until the retry
  * budget is exhausted.
  */
object Resilience {

  /** The combined retry + circuit-breaker `Client[F]` middleware. */
  def middleware[F[_]: Async](
      config: ResilienceConfig
  )(
      logger: StructuredLogger[F]
  )(meter: Meter[F])(client: Client[F]): Client[F] =
    Retry.middleware[F](config.retry)(logger)(meter)(
      CircuitBreaker.middleware[F](config.circuitBreaker)(meter)(client)
    )
}
