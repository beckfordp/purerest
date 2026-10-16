package purekafka.resilience

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.oteljava.testkit.metrics.{
  MetricExpectation,
  MetricExpectations,
  MetricsTestkit,
  PointExpectation
}

import scala.concurrent.duration._

class ResilienceSuite extends CatsEffectSuite {

  private def failingNTimes(counter: Ref[IO, Int], failures: Int): IO[Int] =
    counter.updateAndGet(_ + 1).flatMap { n =>
      if (n <= failures) IO.raiseError(new RuntimeException(s"boom $n"))
      else IO.pure(n)
    }

  test("retries a transiently-failing call and succeeds, same as Retry alone") {
    val config = ResilienceConfig(
      retry = RetryConfig(maxRetries = 2, baseDelay = 1.millisecond),
      circuitBreaker =
        CircuitBreakerConfig(failureThreshold = 10, resetTimeout = 1.hour)
    )
    for {
      counter <- Ref.of[IO, Int](0)
      guard = Resilience.middleware[IO](config)(NoOpLogger[IO])(Meter.noop[IO])
      result <- guard(failingNTimes(counter, failures = 2))
      attempts <- counter.get
    } yield {
      assertEquals(result, 3)
      assertEquals(attempts, 3)
    }
  }

  test(
    "opens the breaker after the configured failure threshold and fails fast without calling through"
  ) {
    // maxRetries = 0 isolates this test from Retry's own looping - each
    // guard(...) call is then exactly one pass through the breaker, so
    // "failureThreshold consecutive failed guard(...) calls" is
    // unambiguous. (With retries enabled, each retry attempt hits the
    // breaker separately too - covered by the next test instead.)
    val config = ResilienceConfig(
      retry = RetryConfig(maxRetries = 0, baseDelay = 1.millisecond),
      circuitBreaker =
        CircuitBreakerConfig(failureThreshold = 2, resetTimeout = 1.hour)
    )
    for {
      counter <- Ref.of[IO, Int](0)
      error = new RuntimeException("boom")
      guard = Resilience.middleware[IO](config)(NoOpLogger[IO])(Meter.noop[IO])
      _ <- guard(counter.update(_ + 1) *> IO.raiseError[Int](error)).attempt
      _ <- guard(counter.update(_ + 1) *> IO.raiseError[Int](error)).attempt
      attemptsBeforeOpen <- counter.get
      resultWhileOpen <- guard(
        counter.update(_ + 1) *> IO.raiseError[Int](error)
      ).attempt
      attemptsAfterOpen <- counter.get
    } yield {
      assertEquals(attemptsBeforeOpen, 2)
      assertEquals(resultWhileOpen, Left(CircuitBreakerOpen))
      assertEquals(attemptsAfterOpen, 2) // 3rd call never reached the underlying fa
    }
  }

  test(
    "a breaker-open rejection is not itself retried"
  ) {
    // failureThreshold = 1 means the breaker opens the instant the first
    // underlying call fails. With maxRetries = 2, Retry's loop allows up to
    // 3 total attempts - if CircuitBreakerOpen were (wrongly) retried like
    // any other error, attempts 2 AND 3 would both hit the now-open
    // breaker and be rejected (2 rejections); since it's correctly
    // excluded, the loop gives up right after the FIRST rejection (1
    // rejection) instead of burning through the rest of the retry budget.
    // The underlying fa-call count can't distinguish these two cases (fa
    // never runs on a rejected attempt either way) - the breaker's own
    // calls_rejected metric can, so this asserts on that directly.
    val config = ResilienceConfig(
      retry = RetryConfig(maxRetries = 2, baseDelay = 1.millisecond),
      circuitBreaker =
        CircuitBreakerConfig(failureThreshold = 1, resetTimeout = 1.hour)
    )
    MetricsTestkit.inMemory[IO]().use { testkit =>
      for {
        meter <- testkit.meterProvider.get(
          "purekafka-resilience-breaker-not-retried-test"
        )
        counter <- Ref.of[IO, Int](0)
        error = new RuntimeException("boom")
        guard = Resilience.middleware[IO](config)(NoOpLogger[IO])(meter)
        result <- guard(
          counter.update(_ + 1) *> IO.raiseError[Int](error)
        ).attempt
        attempts <- counter.get
        metrics <- testkit.collectMetrics
      } yield {
        assertEquals(attempts, 1) // fa called once, before the breaker opened
        assertEquals(result, Left(CircuitBreakerOpen))
        MetricExpectations.checkAll(
          metrics,
          List(
            MetricExpectation
              .sum[Long]("purekafka.circuit_breaker.calls_rejected")
              .containsPoints(PointExpectation.numeric(1))
          )
        ) match {
          case Right(_)         => ()
          case Left(mismatches) => fail(MetricExpectations.format(mismatches))
        }
      }
    }
  }
}
