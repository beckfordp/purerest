// SPDX-License-Identifier: MIT

package purerest.resilience

import cats.effect.{IO, Ref, Resource}
import munit.CatsEffectSuite
import org.http4s.client.Client
import org.http4s.{Request, Response, Status}
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.otel4s.metrics.Meter

import scala.concurrent.duration._

class ResilienceSuite extends CatsEffectSuite {

  private def respondingClient(
      counter: Ref[IO, Int]
  )(behavior: Int => Status): Client[IO] =
    Client[IO] { _ =>
      Resource.eval(
        counter.updateAndGet(_ + 1).map(n => Response[IO](behavior(n)))
      )
    }

  test("retries pass through the circuit breaker on each attempt") {
    for {
      counter <- Ref.of[IO, Int](0)
      client = respondingClient(counter)(n =>
        if (n < 3) Status.InternalServerError else Status.Ok
      )
      config = ResilienceConfig(
        retry = RetryConfig(maxRetries = 5, baseDelay = 1.millisecond),
        circuitBreaker =
          CircuitBreakerConfig(failureThreshold = 10, resetTimeout = 1.hour)
      )
      resilientClient = Resilience.middleware[IO](config)(NoOpLogger[IO])(
        Meter.noop[IO]
      )(
        client
      )
      response <- resilientClient.run(Request[IO]()).use(IO.pure)
      attempts <- counter.get
    } yield {
      assertEquals(response.status, Status.Ok)
      assertEquals(attempts, 3)
    }
  }

  test("a breaker-open rejection is not retried") {
    for {
      counter <- Ref.of[IO, Int](0)
      client = respondingClient(counter)(_ => Status.InternalServerError)
      config = ResilienceConfig(
        retry = RetryConfig(maxRetries = 5, baseDelay = 1.millisecond),
        circuitBreaker =
          CircuitBreakerConfig(failureThreshold = 1, resetTimeout = 1.hour)
      )
      resilientClient = Resilience.middleware[IO](config)(NoOpLogger[IO])(
        Meter.noop[IO]
      )(
        client
      )
      result <- resilientClient.run(Request[IO]()).use(IO.pure).attempt
      attempts <- counter.get
    } yield {
      assertEquals(result, Left(CircuitBreakerOpen))
      assertEquals(
        attempts,
        1
      ) // only the first attempt ever reached the underlying client
    }
  }
}
