package purekafka.consumer

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger.ERROR
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.oteljava.testkit.metrics.{
  MetricExpectation,
  MetricExpectations,
  MetricsTestkit,
  PointExpectation
}
import purekafka.resilience.{
  CircuitBreakerConfig,
  ResilienceConfig,
  RetryConfig
}

import scala.concurrent.duration._

/** Tests [[DeadLetter.fromPublish]] directly, against a fake `publish` function
  * rather than a real Kafka producer — same "test the logic, not the broker"
  * convention `purekafka.resilience.Retry`/`purekafka.consumer.
  * StreamSupervisor` already use for their own synthetic-`F[A]`/synthetic-
  * `Stream` tests. [[DeadLetter.resource]] (the real fs2-kafka-backed
  * constructor) is a thin wrapper over this, not exercised against a real
  * broker here.
  */
class DeadLetterSuite extends CatsEffectSuite {

  private val fastConfig =
    RetryConfig(maxRetries = 2, baseDelay = 1.millisecond)

  // Generous enough (high failure threshold, short reset) not to interfere
  // with tests that aren't themselves exercising the DLQ-publish resilience
  // path - see the two tests below for that.
  private val generousPublishResilience = ResilienceConfig(
    retry = RetryConfig(maxRetries = 2, baseDelay = 1.millisecond),
    circuitBreaker =
      CircuitBreakerConfig(failureThreshold = 100, resetTimeout = 1.second)
  )

  private def recordingPublish(
      log: Ref[IO, List[(String, String, String)]]
  ): (String, String, String) => IO[Unit] =
    (topic, key, value) => log.update(_ :+ (topic, key, value))

  test("succeeds without publishing to the DLQ when the call never fails") {
    for {
      published <- Ref.of[IO, List[(String, String, String)]](Nil)
      deadLetter <- DeadLetter.fromPublish[IO, String, String](
        fastConfig,
        generousPublishResilience,
        recordingPublish(published)
      )(NoOpLogger[IO])(Meter.noop[IO])
      _ <- deadLetter.guard("orders", "k1", "v1")(IO.unit)
      log <- published.get
    } yield assertEquals(log, Nil)
  }

  test(
    "retries a failing call and succeeds once it recovers, without publishing to the DLQ"
  ) {
    for {
      attempts <- Ref.of[IO, Int](0)
      published <- Ref.of[IO, List[(String, String, String)]](Nil)
      deadLetter <- DeadLetter.fromPublish[IO, String, String](
        fastConfig,
        generousPublishResilience,
        recordingPublish(published)
      )(NoOpLogger[IO])(Meter.noop[IO])
      process = attempts.updateAndGet(_ + 1).flatMap { n =>
        if (n <= 2) IO.raiseError(new RuntimeException(s"boom $n"))
        else IO.unit
      }
      _ <- deadLetter.guard("orders", "k1", "v1")(process)
      log <- published.get
      finalAttempts <- attempts.get
    } yield {
      assertEquals(log, Nil)
      assertEquals(finalAttempts, 3) // 1 initial + 2 retries
    }
  }

  test(
    "routes to <topic>-dlq after retries are exhausted, and does not raise"
  ) {
    for {
      published <- Ref.of[IO, List[(String, String, String)]](Nil)
      deadLetter <- DeadLetter.fromPublish[IO, String, String](
        fastConfig,
        generousPublishResilience,
        recordingPublish(published)
      )(NoOpLogger[IO])(Meter.noop[IO])
      result <- deadLetter
        .guard("orders", "k1", "v1")(
          IO.raiseError(new RuntimeException("poison"))
        )
        .attempt
      log <- published.get
    } yield {
      assert(
        result.isRight,
        s"expected guard to swallow the error, got $result"
      )
      assertEquals(log, List(("orders-dlq", "k1", "v1")))
    }
  }

  test(
    "logs the DLQ routing with structured topic/dlq_topic/key context, not just interpolated into the message"
  ) {
    for {
      published <- Ref.of[IO, List[(String, String, String)]](Nil)
      testLogger = StructuredTestingLogger.impl[IO]()
      deadLetter <- DeadLetter.fromPublish[IO, String, String](
        fastConfig,
        generousPublishResilience,
        recordingPublish(published)
      )(testLogger)(Meter.noop[IO])
      _ <- deadLetter
        .guard("orders", "k1", "v1")(
          IO.raiseError(new RuntimeException("poison"))
        )
        .attempt
      logged <- testLogger.logged
    } yield {
      val errors = logged.collect { case m: ERROR => m }
      assert(
        errors.exists(m =>
          m.ctx.get("topic").contains("orders") &&
            m.ctx.get("dlq_topic").contains("orders-dlq") &&
            m.ctx.get("key").contains("k1")
        ),
        s"expected an ERROR line with topic/dlq_topic/key structured context, got: $errors"
      )
    }
  }

  test(
    "records a purekafka.dlq.published metric when routed to the DLQ, and none when it isn't"
  ) {
    MetricsTestkit.inMemory[IO]().use { testkit =>
      for {
        meter <- testkit.meterProvider.get("purekafka-dlq-metrics-test")
        published <- Ref.of[IO, List[(String, String, String)]](Nil)
        deadLetter <- DeadLetter.fromPublish[IO, String, String](
          fastConfig,
          generousPublishResilience,
          recordingPublish(published)
        )(NoOpLogger[IO])(meter)
        _ <- deadLetter.guard("orders", "k1", "v1")(IO.unit) // no DLQ
        _ <- deadLetter
          .guard("orders", "k2", "v2")(
            IO.raiseError(new RuntimeException("poison"))
          )
          .attempt // 1 DLQ publish
        metrics <- testkit.collectMetrics
      } yield {
        val expectations = List(
          MetricExpectation
            .sum[Long]("purekafka.dlq.published")
            .containsPoints(PointExpectation.numeric(1))
        )
        MetricExpectations.checkAll(metrics, expectations) match {
          case Right(_)         => ()
          case Left(mismatches) => fail(MetricExpectations.format(mismatches))
        }
      }
    }
  }

  test(
    "retries the DLQ publish itself on a transient failure, before it succeeds"
  ) {
    for {
      attempts <- Ref.of[IO, Int](0)
      resilientPublish = (_: String, _: String, _: String) =>
        attempts.updateAndGet(_ + 1).flatMap { n =>
          if (n <= 2) IO.raiseError(new RuntimeException(s"dlq boom $n"))
          else IO.unit
        }
      deadLetter <- DeadLetter.fromPublish[IO, String, String](
        fastConfig,
        ResilienceConfig(
          retry = RetryConfig(maxRetries = 2, baseDelay = 1.millisecond),
          circuitBreaker = CircuitBreakerConfig(
            failureThreshold = 100,
            resetTimeout = 1.second
          )
        ),
        resilientPublish
      )(NoOpLogger[IO])(Meter.noop[IO])
      result <- deadLetter
        .guard("orders", "k1", "v1")(
          IO.raiseError(new RuntimeException("poison"))
        )
        .attempt
      finalAttempts <- attempts.get
    } yield {
      assert(
        result.isRight,
        s"expected guard to swallow the processing error once the DLQ publish eventually succeeds, got $result"
      )
      assertEquals(finalAttempts, 3) // 1 initial + 2 retries
    }
  }

  test(
    "raises, rather than swallowing, when the DLQ publish itself is permanently failing after its own retries and circuit breaker are exhausted"
  ) {
    val alwaysFailingPublish = (_: String, _: String, _: String) =>
      IO.raiseError[Unit](new RuntimeException("dlq broker unreachable"))
    for {
      deadLetter <- DeadLetter.fromPublish[IO, String, String](
        fastConfig,
        ResilienceConfig(
          retry = RetryConfig(maxRetries = 1, baseDelay = 1.millisecond),
          circuitBreaker = CircuitBreakerConfig(
            failureThreshold = 100,
            resetTimeout = 1.second
          )
        ),
        alwaysFailingPublish
      )(NoOpLogger[IO])(Meter.noop[IO])
      result <- deadLetter
        .guard("orders", "k1", "v1")(
          IO.raiseError(new RuntimeException("poison"))
        )
        .attempt
    } yield assert(
      result.isLeft,
      s"expected guard to raise once the DLQ publish itself exhausts its retry budget, got $result"
    )
  }
}
