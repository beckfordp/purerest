// SPDX-License-Identifier: MIT

package purerest.resilience

import cats.effect.{IO, Ref, Resource}
import munit.CatsEffectSuite
import org.http4s.client.Client
import org.http4s.{Request, Response, Status}
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.oteljava.testkit.metrics.{
  MetricExpectation,
  MetricExpectations,
  PointExpectation
}
import purerest.metrics.Metrics

import scala.concurrent.duration._

class RetryMetricsSuite extends CatsEffectSuite {

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

  private def assertOutcomeCounts(
      metrics: List[io.opentelemetry.sdk.metrics.data.MetricData],
      retried: Long,
      outcome: String,
      outcomeCount: Long
  ): Unit = {
    val expectations = List(
      MetricExpectation
        .sum[Long]("purerest.retry.attempts")
        .containsPoints(
          PointExpectation
            .numeric(outcomeCount)
            .attributesExact(Attribute("outcome", outcome))
        )
    ) ++ (if (retried > 0)
            List(
              MetricExpectation
                .sum[Long]("purerest.retry.attempts")
                .containsPoints(
                  PointExpectation
                    .numeric(retried)
                    .attributesExact(Attribute("outcome", "retried"))
                )
            )
          else Nil)

    MetricExpectations.checkAll(metrics, expectations) match {
      case Right(_)         => ()
      case Left(mismatches) => fail(MetricExpectations.format(mismatches))
    }
  }

  test("a call that succeeds immediately records only a 'succeeded' attempt") {
    Metrics.test[IO]("purerest-retry-metrics-test").use { testMeter =>
      for {
        counter <- Ref.of[IO, Int](0)
        client = respondingClient(counter)(_ => Status.Ok)
        resilientClient = Retry.middleware[IO](fastConfig)(NoOpLogger[IO])(
          testMeter.meter
        )(client)
        _ <- resilientClient.run(Request[IO]()).use(IO.pure)
        metrics <- testMeter.collectMetrics
      } yield assertOutcomeCounts(
        metrics,
        retried = 0,
        outcome = "succeeded",
        outcomeCount = 1
      )
    }
  }

  test(
    "reuses the same counter instrument across multiple calls on the wrapped client"
  ) {
    Metrics.test[IO]("purerest-retry-metrics-test").use { testMeter =>
      for {
        counter <- Ref.of[IO, Int](0)
        client = respondingClient(counter)(_ => Status.Ok)
        resilientClient = Retry.middleware[IO](fastConfig)(NoOpLogger[IO])(
          testMeter.meter
        )(client)
        _ <- resilientClient.run(Request[IO]()).use(IO.pure)
        _ <- resilientClient.run(Request[IO]()).use(IO.pure)
        metrics <- testMeter.collectMetrics
      } yield assertOutcomeCounts(
        metrics,
        retried = 0,
        outcome = "succeeded",
        outcomeCount = 2
      )
    }
  }

  test("a call that succeeds after retries records 'retried' and 'succeeded'") {
    Metrics.test[IO]("purerest-retry-metrics-test").use { testMeter =>
      for {
        counter <- Ref.of[IO, Int](0)
        client = respondingClient(counter)(n =>
          if (n < 3) Status.InternalServerError else Status.Ok
        )
        resilientClient = Retry.middleware[IO](fastConfig)(NoOpLogger[IO])(
          testMeter.meter
        )(client)
        _ <- resilientClient.run(Request[IO]()).use(IO.pure)
        metrics <- testMeter.collectMetrics
      } yield assertOutcomeCounts(
        metrics,
        retried = 2,
        outcome = "succeeded",
        outcomeCount = 1
      )
    }
  }

  test(
    "a call that exhausts retries on 5xx responses records 'retried' and 'exhausted'"
  ) {
    Metrics.test[IO]("purerest-retry-metrics-test").use { testMeter =>
      for {
        counter <- Ref.of[IO, Int](0)
        client = respondingClient(counter)(_ => Status.InternalServerError)
        resilientClient = Retry.middleware[IO](fastConfig)(NoOpLogger[IO])(
          testMeter.meter
        )(client)
        _ <- resilientClient.run(Request[IO]()).use(IO.pure)
        metrics <- testMeter.collectMetrics
      } yield assertOutcomeCounts(
        metrics,
        retried = 2,
        outcome = "exhausted",
        outcomeCount = 1
      )
    }
  }

  test(
    "a call that exhausts retries on connection errors records 'retried' and 'exhausted'"
  ) {
    Metrics.test[IO]("purerest-retry-metrics-test").use { testMeter =>
      for {
        counter <- Ref.of[IO, Int](0)
        client = failingClient(counter)(new java.net.ConnectException("boom"))
        resilientClient = Retry.middleware[IO](fastConfig)(NoOpLogger[IO])(
          testMeter.meter
        )(client)
        _ <- resilientClient.run(Request[IO]()).use(IO.pure).attempt
        metrics <- testMeter.collectMetrics
      } yield assertOutcomeCounts(
        metrics,
        retried = 2,
        outcome = "exhausted",
        outcomeCount = 1
      )
    }
  }
}
