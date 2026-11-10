// SPDX-License-Identifier: MIT

package purerest.resilience

import cats.effect.{IO, Ref, Resource}
import munit.CatsEffectSuite
import org.http4s.client.Client
import org.http4s.{Request, Response, Status}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.oteljava.testkit.metrics.{
  MetricExpectation,
  MetricExpectations,
  PointExpectation
}
import purerest.metrics.Metrics

import scala.concurrent.duration._

class CircuitBreakerMetricsSuite extends CatsEffectSuite {

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

  test("tripping the breaker open records a CLOSED -> OPEN state transition") {
    Metrics.test[IO]("purerest-circuit-breaker-metrics-test").use { testMeter =>
      for {
        counter <- Ref.of[IO, Int](0)
        client = failingClient(counter)(new RuntimeException("boom"))
        protectedClient = CircuitBreaker.middleware[IO](
          CircuitBreakerConfig(failureThreshold = 2, resetTimeout = 1.hour)
        )(testMeter.meter)(client)
        _ <- protectedClient.run(Request[IO]()).use(IO.pure).attempt
        _ <- protectedClient.run(Request[IO]()).use(IO.pure).attempt
        metrics <- testMeter.collectMetrics
      } yield MetricExpectations.checkAll(
        metrics,
        MetricExpectation
          .sum[Long]("purerest.circuit_breaker.state_transitions")
          .containsPoints(
            PointExpectation
              .numeric(1L)
              .attributesExact(
                Attribute("from_state", "CLOSED"),
                Attribute("to_state", "OPEN")
              )
          )
      ) match {
        case Right(_)         => ()
        case Left(mismatches) => fail(MetricExpectations.format(mismatches))
      }
    }
  }

  test("a rejected call while open records calls_rejected") {
    Metrics.test[IO]("purerest-circuit-breaker-metrics-test").use { testMeter =>
      for {
        counter <- Ref.of[IO, Int](0)
        client = failingClient(counter)(new RuntimeException("boom"))
        protectedClient = CircuitBreaker.middleware[IO](
          CircuitBreakerConfig(failureThreshold = 1, resetTimeout = 1.hour)
        )(testMeter.meter)(client)
        _ <- protectedClient
          .run(Request[IO]())
          .use(IO.pure)
          .attempt // trips the breaker open
        _ <- protectedClient.run(Request[IO]()).use(IO.pure).attempt // rejected
        metrics <- testMeter.collectMetrics
      } yield MetricExpectations.checkAll(
        metrics,
        MetricExpectation
          .sum[Long]("purerest.circuit_breaker.calls_rejected")
          .containsPoints(PointExpectation.numeric(1L))
      ) match {
        case Right(_)         => ()
        case Left(mismatches) => fail(MetricExpectations.format(mismatches))
      }
    }
  }

  test("tripping the breaker open updates the live state gauge to OPEN") {
    Metrics.test[IO]("purerest-circuit-breaker-metrics-test").use { testMeter =>
      for {
        counter <- Ref.of[IO, Int](0)
        client = failingClient(counter)(new RuntimeException("boom"))
        protectedClient = CircuitBreaker.middleware[IO](
          CircuitBreakerConfig(failureThreshold = 2, resetTimeout = 1.hour)
        )(testMeter.meter)(client)
        _ <- protectedClient.run(Request[IO]()).use(IO.pure).attempt
        _ <- protectedClient.run(Request[IO]()).use(IO.pure).attempt
        metrics <- testMeter.collectMetrics
      } yield MetricExpectations.checkAll(
        metrics,
        MetricExpectation
          .gauge[Long]("purerest.circuit_breaker.state")
          .containsPoints(
            PointExpectation
              .numeric(1L)
              .attributesExact(Attribute("state", "OPEN")),
            PointExpectation
              .numeric(0L)
              .attributesExact(Attribute("state", "CLOSED")),
            PointExpectation
              .numeric(0L)
              .attributesExact(Attribute("state", "HALF_OPEN"))
          )
      ) match {
        case Right(_)         => ()
        case Left(mismatches) => fail(MetricExpectations.format(mismatches))
      }
    }
  }

  test(
    "recovering via a half-open trial call records an OPEN -> CLOSED state transition"
  ) {
    Metrics.test[IO]("purerest-circuit-breaker-metrics-test").use { testMeter =>
      for {
        counter <- Ref.of[IO, Int](0)
        shouldFail <- Ref.of[IO, Boolean](true)
        client = flakyClient(counter, shouldFail)(new RuntimeException("boom"))
        protectedClient = CircuitBreaker.middleware[IO](
          CircuitBreakerConfig(failureThreshold = 1, resetTimeout = 50.millis)
        )(testMeter.meter)(client)
        _ <- protectedClient
          .run(Request[IO]())
          .use(IO.pure)
          .attempt // trips the breaker open
        _ <- IO.sleep(100.millis) // past the reset timeout
        _ <- shouldFail.set(false) // the half-open trial call will now succeed
        _ <- protectedClient.run(Request[IO]()).use(IO.pure).attempt
        metrics <- testMeter.collectMetrics
      } yield MetricExpectations.checkAll(
        metrics,
        MetricExpectation
          .sum[Long]("purerest.circuit_breaker.state_transitions")
          .containsPoints(
            PointExpectation
              .numeric(1L)
              .attributesExact(
                Attribute("from_state", "OPEN"),
                Attribute("to_state", "CLOSED")
              )
          )
      ) match {
        case Right(_)         => ()
        case Left(mismatches) => fail(MetricExpectations.format(mismatches))
      }
    }
  }
}
