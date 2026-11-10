// SPDX-License-Identifier: MIT

package purerest.resilience

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._
import munit.CatsEffectSuite
import org.http4s.client.Client
import org.http4s.{Request, Response, Status}
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.otel4s.metrics.Meter

import scala.concurrent.duration._

class RetrySuite extends CatsEffectSuite {

  private def respondingClient(
      counter: Ref[IO, Int]
  )(behavior: Int => Status): Client[IO] =
    Client[IO] { _ =>
      Resource.eval(
        counter.updateAndGet(_ + 1).map(n => Response[IO](behavior(n)))
      )
    }

  private def failingClient(
      counter: Ref[IO, Int]
  )(error: Throwable): Client[IO] =
    Client[IO] { _ =>
      Resource.eval(counter.update(_ + 1) *> IO.raiseError[Response[IO]](error))
    }

  private val fastConfig =
    RetryConfig(maxRetries = 2, baseDelay = 1.millisecond)

  test(
    "retries a 5xx response and succeeds once the underlying client recovers"
  ) {
    for {
      counter <- Ref.of[IO, Int](0)
      client = respondingClient(counter)(n =>
        if (n < 3) Status.InternalServerError else Status.Ok
      )
      resilientClient = Retry.middleware[IO](fastConfig)(NoOpLogger[IO])(
        Meter.noop[IO]
      )(client)
      response <- resilientClient.run(Request[IO]()).use(IO.pure)
      attempts <- counter.get
    } yield {
      assertEquals(response.status, Status.Ok)
      assertEquals(attempts, 3)
    }
  }

  test("gives up after max retries and returns the last 5xx response") {
    for {
      counter <- Ref.of[IO, Int](0)
      client = respondingClient(counter)(_ => Status.InternalServerError)
      resilientClient = Retry.middleware[IO](fastConfig)(NoOpLogger[IO])(
        Meter.noop[IO]
      )(client)
      response <- resilientClient.run(Request[IO]()).use(IO.pure)
      attempts <- counter.get
    } yield {
      assertEquals(response.status, Status.InternalServerError)
      assertEquals(attempts, 3) // 1 initial attempt + 2 retries
    }
  }

  test("never retries a 4xx response") {
    for {
      counter <- Ref.of[IO, Int](0)
      client = respondingClient(counter)(_ => Status.BadRequest)
      resilientClient = Retry.middleware[IO](fastConfig)(NoOpLogger[IO])(
        Meter.noop[IO]
      )(client)
      response <- resilientClient.run(Request[IO]()).use(IO.pure)
      attempts <- counter.get
    } yield {
      assertEquals(response.status, Status.BadRequest)
      assertEquals(attempts, 1)
    }
  }

  test("retries connection errors and raises after exhausting retries") {
    for {
      counter <- Ref.of[IO, Int](0)
      client = failingClient(counter)(new java.net.ConnectException("boom"))
      resilientClient = Retry.middleware[IO](fastConfig)(NoOpLogger[IO])(
        Meter.noop[IO]
      )(client)
      result <- resilientClient.run(Request[IO]()).use(IO.pure).attempt
      attempts <- counter.get
    } yield {
      assert(result.isLeft)
      assertEquals(attempts, 3)
    }
  }

  test("retries timeouts and raises after exhausting retries") {
    for {
      counter <- Ref.of[IO, Int](0)
      client = failingClient(counter)(
        new java.util.concurrent.TimeoutException("boom")
      )
      resilientClient = Retry.middleware[IO](fastConfig)(NoOpLogger[IO])(
        Meter.noop[IO]
      )(client)
      result <- resilientClient.run(Request[IO]()).use(IO.pure).attempt
      attempts <- counter.get
    } yield {
      assert(result.isLeft)
      assertEquals(attempts, 3)
    }
  }

  test("does not retry a non-retriable exception") {
    for {
      counter <- Ref.of[IO, Int](0)
      client = failingClient(counter)(new RuntimeException("boom"))
      resilientClient = Retry.middleware[IO](fastConfig)(NoOpLogger[IO])(
        Meter.noop[IO]
      )(client)
      result <- resilientClient.run(Request[IO]()).use(IO.pure).attempt
      attempts <- counter.get
    } yield {
      assert(result.isLeft)
      assertEquals(attempts, 1)
    }
  }
}
