package purekafka.resilience

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite
import org.typelevel.otel4s.metrics.Meter

import scala.concurrent.duration._

class CircuitBreakerSuite extends CatsEffectSuite {

  private def succeedingNTimes(
      counter: Ref[IO, Int]
  ): IO[Int] = counter.updateAndGet(_ + 1)

  private def failingAlways(
      counter: Ref[IO, Int],
      error: Throwable
  ): IO[Int] =
    counter.updateAndGet(_ + 1) *> IO.raiseError[Int](error)

  test(
    "stays closed and passes calls through while the underlying call succeeds"
  ) {
    for {
      counter <- Ref.of[IO, Int](0)
      guard = CircuitBreaker.middleware[IO](
        CircuitBreakerConfig(failureThreshold = 3, resetTimeout = 50.millis)
      )(Meter.noop[IO])
      _ <- guard(succeedingNTimes(counter))
      _ <- guard(succeedingNTimes(counter))
      _ <- guard(succeedingNTimes(counter))
      attempts <- counter.get
    } yield assertEquals(attempts, 3)
  }

  test(
    "opens after the configured failure threshold and fails fast without calling the underlying call"
  ) {
    for {
      counter <- Ref.of[IO, Int](0)
      error = new RuntimeException("boom")
      guard = CircuitBreaker.middleware[IO](
        CircuitBreakerConfig(failureThreshold = 2, resetTimeout = 1.hour)
      )(Meter.noop[IO])
      _ <- guard(failingAlways(counter, error)).attempt
      _ <- guard(failingAlways(counter, error)).attempt
      attemptsBeforeOpen <- counter.get
      resultWhileOpen <- guard(failingAlways(counter, error)).attempt
      attemptsAfterOpen <- counter.get
    } yield {
      assertEquals(attemptsBeforeOpen, 2)
      assertEquals(resultWhileOpen, Left(CircuitBreakerOpen))
      assertEquals(
        attemptsAfterOpen,
        2
      ) // the underlying call was never made a 3rd time
    }
  }

  test(
    "transitions to half-open after the reset timeout and closes again on a successful trial call"
  ) {
    for {
      counter <- Ref.of[IO, Int](0)
      shouldFail <- Ref.of[IO, Boolean](true)
      error = new RuntimeException("boom")
      flaky = counter.updateAndGet(_ + 1) *> shouldFail.get.flatMap { fail =>
        if (fail) IO.raiseError[Int](error) else IO.pure(0)
      }
      guard = CircuitBreaker.middleware[IO](
        CircuitBreakerConfig(failureThreshold = 1, resetTimeout = 50.millis)
      )(Meter.noop[IO])
      _ <- guard(flaky).attempt // trips the breaker open
      openResult <- guard(flaky).attempt
      attemptsWhileOpen <- counter.get
      _ <- IO.sleep(100.millis) // past the reset timeout
      _ <- shouldFail.set(false) // the trial call will now succeed
      trialResult <- guard(flaky).attempt
      closedResult <- guard(flaky).attempt
      finalAttempts <- counter.get
    } yield {
      assertEquals(openResult, Left(CircuitBreakerOpen))
      assertEquals(
        attemptsWhileOpen,
        1
      ) // only the first (failing) call ever reached the underlying call
      assert(trialResult.isRight) // half-open trial call succeeds
      assert(closedResult.isRight) // breaker closed again, calls flow normally
      assertEquals(finalAttempts, 3)
    }
  }
}
