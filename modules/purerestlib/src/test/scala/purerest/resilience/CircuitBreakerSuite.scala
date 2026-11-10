// SPDX-License-Identifier: MIT

package purerest.resilience

import cats.effect.{IO, Ref, Resource}
import munit.CatsEffectSuite
import org.http4s.client.Client
import org.http4s.{Request, Response, Status}
import org.typelevel.otel4s.metrics.Meter

import scala.concurrent.duration._

class CircuitBreakerSuite extends CatsEffectSuite {

  private def respondingClient(
      counter: Ref[IO, Int]
  )(status: Status): Client[IO] =
    Client[IO] { _ =>
      Resource.eval(counter.update(_ + 1).as(Response[IO](status)))
    }

  private def failingClient(
      counter: Ref[IO, Int]
  )(error: Throwable): Client[IO] =
    Client[IO] { _ =>
      Resource.eval(counter.update(_ + 1) *> IO.raiseError[Response[IO]](error))
    }

  private def flakyClient(counter: Ref[IO, Int], shouldFail: Ref[IO, Boolean])(
      error: Throwable
  ): Client[IO] =
    Client[IO] { _ =>
      Resource.eval(counter.update(_ + 1) *> shouldFail.get.flatMap { fail =>
        if (fail) IO.raiseError[Response[IO]](error)
        else IO.pure(Response[IO](Status.Ok))
      })
    }

  test(
    "isFailureResult treats a 5xx response as a failure and a non-Response value as not"
  ) {
    assert(
      CircuitBreaker.isFailureResult.test(
        Response[IO](Status.InternalServerError)
      )
    )
    assert(!CircuitBreaker.isFailureResult.test(Response[IO](Status.Ok)))
    assert(!CircuitBreaker.isFailureResult.test("not a response"))
  }

  test(
    "stays closed and passes calls through while the underlying client succeeds"
  ) {
    for {
      counter <- Ref.of[IO, Int](0)
      client = respondingClient(counter)(Status.Ok)
      protectedClient =
        CircuitBreaker.middleware[IO](
          CircuitBreakerConfig(failureThreshold = 3, resetTimeout = 50.millis)
        )(Meter.noop[IO])(client)
      _ <- protectedClient.run(Request[IO]()).use(IO.pure)
      _ <- protectedClient.run(Request[IO]()).use(IO.pure)
      _ <- protectedClient.run(Request[IO]()).use(IO.pure)
      attempts <- counter.get
    } yield assertEquals(attempts, 3)
  }

  test(
    "opens after the configured failure threshold and fails fast without calling the underlying client"
  ) {
    for {
      counter <- Ref.of[IO, Int](0)
      client = failingClient(counter)(new RuntimeException("boom"))
      protectedClient =
        CircuitBreaker.middleware[IO](
          CircuitBreakerConfig(failureThreshold = 2, resetTimeout = 1.hour)
        )(Meter.noop[IO])(client)
      _ <- protectedClient.run(Request[IO]()).use(IO.pure).attempt
      _ <- protectedClient.run(Request[IO]()).use(IO.pure).attempt
      attemptsBeforeOpen <- counter.get
      resultWhileOpen <- protectedClient.run(Request[IO]()).use(IO.pure).attempt
      attemptsAfterOpen <- counter.get
    } yield {
      assertEquals(attemptsBeforeOpen, 2)
      assertEquals(resultWhileOpen, Left(CircuitBreakerOpen))
      assertEquals(
        attemptsAfterOpen,
        2
      ) // the underlying client was never called a 3rd time
    }
  }

  test(
    "transitions to half-open after the reset timeout and closes again on a successful trial call"
  ) {
    for {
      counter <- Ref.of[IO, Int](0)
      shouldFail <- Ref.of[IO, Boolean](true)
      client = flakyClient(counter, shouldFail)(new RuntimeException("boom"))
      protectedClient =
        CircuitBreaker.middleware[IO](
          CircuitBreakerConfig(failureThreshold = 1, resetTimeout = 50.millis)
        )(Meter.noop[IO])(client)
      _ <- protectedClient
        .run(Request[IO]())
        .use(IO.pure)
        .attempt // trips the breaker open
      openResult <- protectedClient.run(Request[IO]()).use(IO.pure).attempt
      attemptsWhileOpen <- counter.get
      _ <- IO.sleep(100.millis) // past the reset timeout
      _ <- shouldFail.set(
        false
      ) // the trial call in half-open state will now succeed
      trialResult <- protectedClient
        .run(Request[IO]())
        .use(response => IO.pure(response.status))
        .attempt
      closedResult <- protectedClient
        .run(Request[IO]())
        .use(response => IO.pure(response.status))
        .attempt
      finalAttempts <- counter.get
    } yield {
      assertEquals(openResult, Left(CircuitBreakerOpen))
      assertEquals(
        attemptsWhileOpen,
        1
      ) // only the first (failing) call ever reached the underlying client
      assertEquals(
        trialResult,
        Right(Status.Ok)
      ) // half-open trial call succeeds
      assertEquals(
        closedResult,
        Right(Status.Ok)
      ) // breaker closed again, calls flow through normally
      assertEquals(finalAttempts, 3)
    }
  }
}
