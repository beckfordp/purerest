package purekafka.consumer

import cats.effect.IO
import munit.CatsEffectSuite
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.oteljava.testkit.metrics.{
  MetricExpectation,
  MetricExpectations,
  PointExpectation
}
import org.typelevel.otel4s.oteljava.testkit.metrics.MetricsTestkit

import scala.concurrent.duration._

class ConsumerLagSuite extends CatsEffectSuite {

  // Local test-meter setup, deliberately not importing purerestlib's own
  // `purerest.metrics.Metrics.test` helper — this module has no dependency
  // on purerestlib (ADR 0010), so it builds the same shape itself.
  private def testMeter = MetricsTestkit.inMemory[IO]()

  test(
    "records the lag gauge for every topic/partition the synthetic supplier reports"
  ) {
    testMeter.use { testkit =>
      for {
        meter <- testkit.meterProvider.get("consumer-lag-test")
        config = ConsumerLagConfig(
          pollInterval = 10.millis,
          groupId = "my-group"
        )
        fetchLag = IO.pure(Map(("my-topic", 0) -> 42.0))
        _ <- ConsumerLag
          .record[IO](config)(meter)(fetchLag)
          .take(1)
          .compile
          .drain
        metrics <- testkit.collectMetrics
      } yield MetricExpectations.checkAll(
        metrics,
        MetricExpectation
          .gauge[Double]("purekafka.consumer.lag")
          .containsPoints(
            PointExpectation
              .numeric(42.0)
              .attributesExact(
                Attribute("messaging.destination.name", "my-topic"),
                Attribute("messaging.kafka.destination.partition", "0"),
                Attribute("messaging.consumer.group.name", "my-group")
              )
          )
      ) match {
        case Right(_)         => ()
        case Left(mismatches) => fail(MetricExpectations.format(mismatches))
      }
    }
  }
}
