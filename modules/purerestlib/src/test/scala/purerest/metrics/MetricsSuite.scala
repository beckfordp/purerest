// SPDX-License-Identifier: MIT

package purerest.metrics

import cats.effect.IO
import munit.CatsEffectSuite
import purerest.client.HttpClient

class MetricsSuite extends CatsEffectSuite {

  test("test meter records a manually-emitted counter value") {
    Metrics.test[IO]("purerest-test").use { testMeter =>
      for {
        counter <- testMeter.meter.counter[Long]("my-counter").create
        _ <- counter.add(1L)
        metrics <- testMeter.collectMetrics
      } yield assertEquals(metrics.map(_.getName), List("my-counter"))
    }
  }

  test(
    "oteljava meter exposes recorded metrics on its Prometheus scrape endpoint"
  ) {
    val scrapePort = 19099
    Metrics.oteljava[IO]("purerest-oteljava-test", scrapePort).use { meter =>
      HttpClient.resource[IO].use { client =>
        for {
          counter <- meter.counter[Long]("purerest_metrics_suite_probe").create
          _ <- counter.add(1L)
          body <- client.expect[String](
            s"http://localhost:${scrapePort}/metrics"
          )
        } yield assert(
          body.contains("purerest_metrics_suite_probe"),
          s"expected the scrape endpoint to include purerest_metrics_suite_probe, got: $body"
        )
      }
    }
  }
}
