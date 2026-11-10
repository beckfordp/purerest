// SPDX-License-Identifier: MIT

package purerest.resilience

import cats.effect.{Async, Ref, Temporal}
import cats.effect.kernel.Resource
import cats.syntax.all._
import org.http4s.Response
import org.http4s.client.Client
import org.http4s.client.middleware.{
  Retry => Http4sRetry,
  RetryPolicy => Http4sRetryPolicy
}
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.noop.NoOpFactory
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{Counter, Meter}
import retry.{PolicyDecision, RetryPolicies, RetryStatus}

import scala.concurrent.duration.{Duration, FiniteDuration}

/** Config for [[Retry.middleware]]. `maxRetries` is retries, not attempts (so
  * `maxRetries = 2` means up to 3 total attempts). `baseDelay` is doubled on
  * each successive retry (exponential backoff, via cats-retry's
  * `RetryPolicies`).
  */
final case class RetryConfig(maxRetries: Int, baseDelay: FiniteDuration)

/** Retries requests that fail transiently: 5xx responses, connection errors,
  * and timeouts. Never retries 4xx responses (a bad request can't succeed by
  * repeating it) or other exception types.
  *
  * Delegates the actual retry loop to http4s's own `Retry` client middleware,
  * which already solves the tricky problem of safely retrying a
  * `Resource`-based `Client[F]` (releasing each failed attempt's
  * connection/body before trying again). cats-retry's `RetryPolicies` supplies
  * the backoff schedule (composed under `cats.Id` since http4s's own backoff
  * slot is a pure function, incompatible with cats-retry's `Random`-effectful
  * jitter policies — so this uses `limitRetries` + `exponentialBackoff`, not
  * `fullJitter`).
  */
object Retry {

  /** True for connection errors and timeouts — the exception types this
    * middleware retries.
    */
  def isRetriableError(error: Throwable): Boolean = error match {
    case _: java.net.ConnectException             => true
    case _: java.util.concurrent.TimeoutException => true
    case _                                        => false
  }

  /** True for 5xx responses — the response class this middleware retries. */
  def isRetriableResponse[F[_]](response: Response[F]): Boolean =
    response.status.responseClass == org.http4s.Status.ServerError

  private def backoff(config: RetryConfig): Int => Option[FiniteDuration] = {
    val policy = RetryPolicies
      .limitRetries[cats.Id](config.maxRetries)
      .join(RetryPolicies.exponentialBackoff[cats.Id](config.baseDelay))
    attempts =>
      policy.decideNextRetry(
        (),
        RetryStatus(retriesSoFar = attempts - 1, Duration.Zero, None)
      ) match {
        case PolicyDecision.DelayAndRetry(delay) => Some(delay)
        case PolicyDecision.GiveUp               => None
      }
  }

  /** The retrying `Client[F]` middleware described above. */
  def middleware[F[_]: Async](
      config: RetryConfig
  )(
      logger: StructuredLogger[F]
  )(meter: Meter[F])(client: Client[F]): Client[F] = {
    implicit val loggerFactory: org.typelevel.log4cats.LoggerFactory[F] =
      NoOpFactory[F]
    val policy = Http4sRetryPolicy[F](
      backoff(config),
      retriable = (_, result) =>
        result match {
          case Right(response) => isRetriableResponse(response)
          case Left(error)     => isRetriableError(error)
        }
    )

    val attemptsCounterRef: Ref[F, Option[Counter[F, Long]]] =
      Ref.unsafe(None)

    /** Records `purerest.retry.attempts`: for a request that took `attempts`
      * total tries and ended in `finalOutcome` ("succeeded" or "exhausted"),
      * the `attempts - 1` earlier tries are recorded as `outcome = "retried"`
      * and the final one as `outcome = finalOutcome`. Derived from the
      * aggregate attempt count http4s's `Retry` middleware reports — this
      * middleware only observes the overall outcome, not each individual
      * attempt. The counter instrument is created once, on first use, and
      * reused for every subsequent call rather than recreated per call.
      */
    def recordAttempts(attempts: Int, finalOutcome: String): F[Unit] =
      attemptsCounterRef.get
        .flatMap {
          case Some(counter) => counter.pure[F]
          case None          =>
            meter
              .counter[Long]("purerest.retry.attempts")
              .create
              .flatTap(counter => attemptsCounterRef.set(Some(counter)))
        }
        .flatMap { counter =>
          val retried = (attempts - 1).toLong
          (if (retried > 0)
             counter.add(retried, Attribute("outcome", "retried"))
           else Temporal[F].unit) *> counter.inc(
            Attribute("outcome", finalOutcome)
          )
        }

    Client[F] { req =>
      Resource.eval(Ref.of[F, Int](0)).flatMap { attemptCounter =>
        // A thin counting wrapper around the underlying client, so the real
        // per-request attempt count is known on every path (including a
        // non-retriable error's single attempt) — http4s's Retry only reports
        // an attempt count via response attributes, which doesn't exist on the
        // error path.
        val countingClient = Client[F] { r =>
          Resource.eval(attemptCounter.update(_ + 1)) *> client.run(r)
        }
        val retried =
          Http4sRetry.create(policy, logRetries = false)(countingClient)
        retried
          .run(req)
          .evalMap { response =>
            attemptCounter.get.flatMap { attempts =>
              val finalOutcome =
                if (isRetriableResponse(response)) "exhausted" else "succeeded"
              val logIfRetried =
                if (attempts > 1)
                  logger.info(
                    s"Request to ${req.uri} succeeded after $attempts attempt(s)"
                  )
                else Temporal[F].unit
              recordAttempts(attempts, finalOutcome) *> logIfRetried.as(
                response
              )
            }
          }
          .onError { case error =>
            Resource.eval(
              attemptCounter.get.flatMap { attempts =>
                recordAttempts(attempts, "exhausted") *>
                  logger
                    .warn(error)(s"Request to ${req.uri} failed after retries")
              }
            )
          }
      }
    }
  }
}
