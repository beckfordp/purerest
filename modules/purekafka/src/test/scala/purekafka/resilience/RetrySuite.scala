package purekafka.resilience

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.oteljava.context.LocalContextProvider
import org.typelevel.otel4s.oteljava.testkit.metrics.{
  MetricExpectation,
  MetricExpectations,
  MetricsTestkit,
  PointExpectation
}

import scala.concurrent.duration._

class RetrySuite extends CatsEffectSuite {

  private val fastConfig =
    RetryConfig(maxRetries = 2, baseDelay = 1.millisecond)

  private def failingNTimes(counter: Ref[IO, Int], failures: Int): IO[Int] =
    counter.updateAndGet(_ + 1).flatMap { n =>
      if (n <= failures) IO.raiseError(new RuntimeException(s"boom $n"))
      else IO.pure(n)
    }

  test("succeeds immediately with a single attempt when the call never fails") {
    for {
      counter <- Ref.of[IO, Int](0)
      guard = Retry.middleware[IO](fastConfig)(NoOpLogger[IO])(Meter.noop[IO])
      result <- guard(failingNTimes(counter, failures = 0))
      attempts <- counter.get
    } yield {
      assertEquals(result, 1)
      assertEquals(attempts, 1)
    }
  }

  test("retries a failing call and succeeds once it recovers") {
    for {
      counter <- Ref.of[IO, Int](0)
      guard = Retry.middleware[IO](fastConfig)(NoOpLogger[IO])(Meter.noop[IO])
      result <- guard(failingNTimes(counter, failures = 2))
      attempts <- counter.get
    } yield {
      assertEquals(result, 3)
      assertEquals(attempts, 3) // 1 initial attempt + 2 retries
    }
  }

  test("gives up after max retries and raises the last error") {
    for {
      counter <- Ref.of[IO, Int](0)
      guard = Retry.middleware[IO](fastConfig)(NoOpLogger[IO])(Meter.noop[IO])
      result <- guard(failingNTimes(counter, failures = Int.MaxValue)).attempt
      attempts <- counter.get
    } yield {
      assert(result.isLeft)
      assertEquals(attempts, 3) // 1 initial attempt + 2 retries, then gives up
    }
  }

  test("retries any raised error, not just a selected subset") {
    // Unlike purerest.resilience.Retry (HTTP-specific: only connection errors
    // and timeouts), a Kafka producer call always raises on failure - every
    // error type is retriable here.
    for {
      counter <- Ref.of[IO, Int](0)
      guard = Retry.middleware[IO](fastConfig)(NoOpLogger[IO])(Meter.noop[IO])
      result <- guard(
        counter.updateAndGet(_ + 1).flatMap { n =>
          if (n == 1) IO.raiseError(new IllegalStateException("not a timeout"))
          else IO.pure(n)
        }
      )
      attempts <- counter.get
    } yield {
      assertEquals(result, 2)
      assertEquals(attempts, 2)
    }
  }

  test("does not retry an error excluded via nonRetriable") {
    for {
      counter <- Ref.of[IO, Int](0)
      guard = Retry.middleware[IO](fastConfig)(NoOpLogger[IO])(
        Meter.noop[IO],
        nonRetriable = _.getMessage == "stop"
      )
      result <- guard(
        counter.update(_ + 1) *> IO.raiseError[Int](
          new RuntimeException("stop")
        )
      ).attempt
      attempts <- counter.get
    } yield {
      assert(result.isLeft)
      assertEquals(attempts, 1) // never retried
    }
  }

  private def assertOutcomeCounts(
      metrics: List[io.opentelemetry.sdk.metrics.data.MetricData],
      retried: Long,
      outcome: String,
      outcomeCount: Long
  ): Unit = {
    val expectations = List(
      MetricExpectation
        .sum[Long]("purekafka.retry.attempts")
        .containsPoints(
          PointExpectation
            .numeric(outcomeCount)
            .attributesExact(Attribute("outcome", outcome))
        )
    ) ++ (if (retried > 0)
            List(
              MetricExpectation
                .sum[Long]("purekafka.retry.attempts")
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

  test(
    "records retry attempts as a purekafka.retry.attempts metric, reusing the same instrument across calls"
  ) {
    MetricsTestkit.inMemory[IO]().use { testkit =>
      for {
        meter <- testkit.meterProvider.get("purekafka-retry-metrics-test")
        counter <- Ref.of[IO, Int](0)
        guard = Retry.middleware[IO](fastConfig)(NoOpLogger[IO])(meter)
        _ <- guard(
          failingNTimes(counter, failures = 2)
        ) // 2 retried, 1 succeeded
        counter2 <- Ref.of[IO, Int](0)
        _ <- guard(
          failingNTimes(counter2, failures = 0)
        ) // 1 succeeded, no retries
        metrics <- testkit.collectMetrics
      } yield assertOutcomeCounts(
        metrics,
        retried = 2,
        outcome = "succeeded",
        outcomeCount = 2
      )
    }
  }

  test(
    "records an exhausted outcome when retries run out"
  ) {
    MetricsTestkit.inMemory[IO]().use { testkit =>
      for {
        meter <- testkit.meterProvider.get("purekafka-retry-metrics-test")
        counter <- Ref.of[IO, Int](0)
        guard = Retry.middleware[IO](fastConfig)(NoOpLogger[IO])(meter)
        _ <- guard(failingNTimes(counter, failures = Int.MaxValue)).attempt
        metrics <- testkit.collectMetrics
      } yield assertOutcomeCounts(
        metrics,
        retried = 2,
        outcome = "exhausted",
        outcomeCount = 1
      )
    }
  }
}
