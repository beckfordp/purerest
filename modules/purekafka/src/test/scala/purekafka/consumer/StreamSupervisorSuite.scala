package purekafka.consumer

import cats.effect.{IO, Ref}
import fs2.Stream
import munit.CatsEffectSuite
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.oteljava.testkit.metrics.{
  MetricExpectation,
  MetricExpectations,
  MetricsTestkit,
  PointExpectation
}

import scala.concurrent.duration._

class StreamSupervisorSuite extends CatsEffectSuite {

  private val fastConfig =
    StreamSupervisorConfig(baseDelay = 1.millisecond, maxDelay = 2.millisecond)

  /** A `Stream[F, Int]` that fails on construction the first `failures` times
    * it's (re)built, then succeeds — simulating a consumer stream that dies and
    * must be rebuilt from scratch on restart (e.g. re-subscribing to Kafka),
    * not just retried as a single effect.
    */
  private def failingNTimes(
      counter: Ref[IO, Int],
      failures: Int
  ): Stream[IO, Int] =
    Stream.eval(counter.updateAndGet(_ + 1)).flatMap { n =>
      if (n <= failures) Stream.raiseError[IO](new RuntimeException(s"boom $n"))
      else Stream.emit(n)
    }

  test("pure backoff doubles the delay each restart, capped at maxDelay") {
    val config =
      StreamSupervisorConfig(baseDelay = 1.second, maxDelay = 5.seconds)
    assertEquals(StreamSupervisor.nextDelay(1.second, config), 2.seconds)
    assertEquals(StreamSupervisor.nextDelay(2.seconds, config), 4.seconds)
    // 4s * 2 = 8s, capped to maxDelay (5s)
    assertEquals(StreamSupervisor.nextDelay(4.seconds, config), 5.seconds)
    // already at the cap - doubling it still can't exceed maxDelay
    assertEquals(StreamSupervisor.nextDelay(5.seconds, config), 5.seconds)
  }

  test("a stream that never fails runs through with no restarts") {
    for {
      counter <- Ref.of[IO, Int](0)
      restarts <- Ref.of[IO, Int](0)
      results <- StreamSupervisor
        .supervise[IO, Int](fastConfig)(NoOpLogger[IO])(Meter.noop[IO]) {
          restarts.update(_ + 1) *> IO.pure(
            failingNTimes(counter, failures = 0)
          )
        }
        .compile
        .toList
      restartCount <- restarts.get
    } yield {
      assertEquals(results, List(1))
      assertEquals(restartCount, 1) // built once, never rebuilt
    }
  }

  test("restarts a failing stream with backoff and eventually succeeds") {
    for {
      counter <- Ref.of[IO, Int](0)
      restarts <- Ref.of[IO, Int](0)
      results <- StreamSupervisor
        .supervise[IO, Int](fastConfig)(NoOpLogger[IO])(Meter.noop[IO]) {
          restarts.update(_ + 1) *> IO.pure(
            failingNTimes(counter, failures = 3)
          )
        }
        .compile
        .toList
      restartCount <- restarts.get
    } yield {
      assertEquals(results, List(4))
      assertEquals(restartCount, 4) // 1 initial build + 3 restarts
    }
  }

  test("keeps restarting indefinitely - no attempt limit") {
    // "Unbounded" can't be proven to infinity, but proving it survives well
    // past any bounded RetryConfig-style limit (Phase 1's own max is small)
    // demonstrates there's no hidden cap here.
    for {
      counter <- Ref.of[IO, Int](0)
      results <- StreamSupervisor
        .supervise[IO, Int](fastConfig)(NoOpLogger[IO])(Meter.noop[IO]) {
          IO.pure(failingNTimes(counter, failures = 25))
        }
        .compile
        .toList
      attempts <- counter.get
    } yield {
      assertEquals(results, List(26))
      assertEquals(attempts, 26)
    }
  }

  test(
    "records a purekafka.stream.restarts metric for each rebuild after a failure"
  ) {
    MetricsTestkit.inMemory[IO]().use { testkit =>
      for {
        meter <- testkit.meterProvider.get("purekafka-stream-supervisor-test")
        counter <- Ref.of[IO, Int](0)
        _ <- StreamSupervisor
          .supervise[IO, Int](fastConfig)(NoOpLogger[IO])(meter) {
            IO.pure(failingNTimes(counter, failures = 2))
          }
          .compile
          .drain
        metrics <- testkit.collectMetrics
      } yield {
        val expectations = List(
          MetricExpectation
            .sum[Long]("purekafka.stream.restarts")
            .containsPoints(PointExpectation.numeric(2))
        )
        MetricExpectations.checkAll(metrics, expectations) match {
          case Right(_)         => ()
          case Left(mismatches) => fail(MetricExpectations.format(mismatches))
        }
      }
    }
  }
}
