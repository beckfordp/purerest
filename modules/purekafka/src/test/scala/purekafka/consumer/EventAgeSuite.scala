// SPDX-License-Identifier: MIT

package purekafka.consumer

import cats.effect.IO
import munit.CatsEffectSuite
import org.typelevel.otel4s.oteljava.testkit.metrics.MetricsTestkit

import scala.jdk.CollectionConverters._

class EventAgeSuite extends CatsEffectSuite {

  private def histogramPoints(name: String)(
      run: EventAge[IO] => IO[Unit]
  ) =
    MetricsTestkit.inMemory[IO]().use { testkit =>
      for {
        meter <- testkit.meterProvider.get("event-age-test")
        eventAge <- EventAge.create[IO](meter)
        _ <- run(eventAge)
        metrics <- testkit.collectMetrics
      } yield metrics
        .find(_.getName == name)
        .map(_.getHistogramData.getPoints.asScala.toList)
        .getOrElse(Nil)
    }

  test("records now minus the record timestamp, in seconds, with attributes") {
    for {
      now <- IO.realTime
      points <- histogramPoints("purekafka.consumer.event.age")(
        _.record("order-events", "order-service", now.toMillis - 2000)
      )
    } yield {
      assertEquals(points.size, 1)
      val p = points.head
      assert(p.getSum >= 2.0 && p.getSum < 3.0, s"sum ${p.getSum}")
      val attrs =
        p.getAttributes.asMap.asScala.map((k, v) => k.getKey -> v.toString)
      assertEquals(
        attrs.toMap,
        Map(
          "messaging.destination.name" -> "order-events",
          "messaging.consumer.group.name" -> "order-service"
        )
      )
      assertEquals(p.getBoundaries.asScala.head.doubleValue, 0.01)
      assertEquals(p.getBoundaries.asScala.last.doubleValue, 600.0)
    }
  }

  test("clamps a timestamp in the future (clock skew) to zero") {
    for {
      now <- IO.realTime
      points <- histogramPoints("purekafka.consumer.event.age")(
        _.record("t", "g", now.toMillis + 60000)
      )
    } yield assertEquals(points.map(_.getSum), List(0.0))
  }
}
