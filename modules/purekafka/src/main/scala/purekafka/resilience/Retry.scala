package purekafka.resilience

import cats.effect.{Async, Ref}
import cats.syntax.all._
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{Counter, Meter}
import retry.{ResultHandler, RetryPolicies}

import scala.concurrent.duration.FiniteDuration

/** Config for [[Retry.middleware]]. `maxRetries` is retries, not attempts (so
  * `maxRetries = 2` means up to 3 total attempts). `baseDelay` is doubled on
  * each successive retry (exponential backoff, via cats-retry's
  * `RetryPolicies`) — same shape as `purerest.resilience.Retry`'s
  * `RetryConfig`.
  */
final case class RetryConfig(maxRetries: Int, baseDelay: FiniteDuration)

/** Retries a generic `F[A]` call on *any* raised error. Unlike
  * `purerest.resilience.Retry` (HTTP-specific — retries only 5xx responses and
  * connection/timeout exceptions, since a 5xx is a normal returned value, not a
  * thrown exception), a Kafka producer call has no "successful-looking value
  * that's actually a failure" case: a failed send always raises, so every error
  * is retriable here.
  *
  * Hand-rolled directly via cats-retry's `retryingOnErrors` rather than
  * delegating to http4s's `Retry` client middleware (not applicable to a
  * non-HTTP call) — the same pattern gluon's own `OrderEventPublisher`/
  * `PaymentEventPublisher` already use directly for their own
  * bounded-retry-then-drop Kafka publish calls.
  */
object Retry {

  /** The retrying [[Guard]], described above. Constructed once per
    * [[RetryConfig]]/logger/meter triple — its `purekafka.retry.attempts`
    * metric instrument is created once and reused across every subsequent call,
    * same convention `purerest.resilience.Retry` uses for its own
    * `purerest.retry.attempts` metric.
    */
  def middleware[F[_]: Async](
      config: RetryConfig
  )(
      logger: StructuredLogger[F]
  )(meter: Meter[F]): Guard[F] = {
    val policy = RetryPolicies
      .limitRetries[F](config.maxRetries)
      .join(RetryPolicies.exponentialBackoff[F](config.baseDelay))

    val counterRef: Ref[F, Option[Counter[F, Long]]] = Ref.unsafe(None)

    def getCounter: F[Counter[F, Long]] =
      counterRef.get.flatMap {
        case Some(counter) => counter.pure[F]
        case None          =>
          meter
            .counter[Long]("purekafka.retry.attempts")
            .create
            .flatTap(counter => counterRef.set(Some(counter)))
      }

    /** Records `purekafka.retry.attempts`: for a call that took `attempts`
      * total tries and ended in `finalOutcome` ("succeeded" or "exhausted"),
      * the `attempts - 1` earlier tries are recorded as `outcome = "retried"`
      * and the final one as `outcome = finalOutcome` — same shape
      * `purerest.resilience.Retry`'s own `recordAttempts` uses.
      */
    def recordAttempts(attempts: Int, finalOutcome: String): F[Unit] =
      getCounter.flatMap { counter =>
        val retried = (attempts - 1).toLong
        (if (retried > 0)
           counter.add(retried, Attribute("outcome", "retried"))
         else Async[F].unit) *> counter.inc(Attribute("outcome", finalOutcome))
      }

    new Guard[F] {
      def apply[A](fa: F[A]): F[A] =
        Ref.of[F, Int](0).flatMap { attemptCounter =>
          val counted = attemptCounter.update(_ + 1) *> fa
          retry
            .retryingOnErrors(counted)(
              policy,
              ResultHandler.retryOnAllErrors[F, A] { (error, details) =>
                logger.warn(error)(
                  s"Call failed (attempt ${details.retriesSoFar + 1}), retrying"
                )
              }
            )
            .attempt
            .flatMap { result =>
              attemptCounter.get
                .flatMap { attempts =>
                  val finalOutcome = result match {
                    case Right(_) => "succeeded"
                    case Left(_)  => "exhausted"
                  }
                  recordAttempts(attempts, finalOutcome)
                }
                .as(result)
            }
            .flatMap(Async[F].fromEither)
        }
    }
  }
}
