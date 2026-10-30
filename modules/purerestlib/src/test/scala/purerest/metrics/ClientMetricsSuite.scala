package purerest.metrics

import cats.effect.{IO, Resource}
import munit.CatsEffectSuite
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.{HttpApp, Request}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.oteljava.testkit.metrics.{
  MetricExpectation,
  MetricExpectations,
  PointExpectation
}

class ClientMetricsSuite extends CatsEffectSuite {

  private val stubApp: HttpApp[IO] = HttpApp { _ => Ok("pong") }

  test(
    "wrapped client records a request duration measurement for a completed call"
  ) {
    Metrics.test[IO]("purerest-client-metrics-test").use { testMeter =>
      val client = Client.fromHttpApp(stubApp)
      val wrapped = ClientMetrics.middleware(testMeter.meter)(client)
      for {
        _ <- wrapped.run(Request[IO](uri = uri"/ping")).use_
        metrics <- testMeter.collectMetrics
      } yield MetricExpectations.checkAll(
        metrics,
        MetricExpectation
          .histogram("http.client.request.duration")
          .containsPoints(
            PointExpectation.histogram
              .attributesSubset(
                Attribute("http.request.method", "GET"),
                Attribute("http.response.status_code", 200L)
              )
          )
      ) match {
        case Right(_)         => ()
        case Left(mismatches) => fail(MetricExpectations.format(mismatches))
      }
    }
  }

  test(
    "reuses the same histogram instrument across multiple calls on the wrapped client"
  ) {
    Metrics.test[IO]("purerest-client-metrics-test").use { testMeter =>
      val client = Client.fromHttpApp(stubApp)
      val wrapped = ClientMetrics.middleware(testMeter.meter)(client)
      for {
        _ <- wrapped.run(Request[IO](uri = uri"/ping")).use_
        _ <- wrapped.run(Request[IO](uri = uri"/ping")).use_
        metrics <- testMeter.collectMetrics
      } yield {
        val data = metrics.find(_.getName == "http.client.request.duration")
        assert(
          data.isDefined,
          s"expected an http.client.request.duration series, got: $metrics"
        )
        // Both calls share identical attributes, so a reused instrument aggregates
        // them into a single point (count=2) rather than one point per call.
        val points = data.get.getHistogramData.getPoints
        assertEquals(points.size, 1)
        assertEquals(points.iterator.next.getCount, 2L)
      }
    }
  }

  test(
    "a call that raises still records a duration measurement, tagged with error.type"
  ) {
    Metrics.test[IO]("purerest-client-metrics-test").use { testMeter =>
      val client = Client[IO] { _ =>
        Resource.eval(IO.raiseError(new java.net.ConnectException("boom")))
      }
      val wrapped = ClientMetrics.middleware(testMeter.meter)(client)
      for {
        result <- wrapped.run(Request[IO](uri = uri"/ping")).use_.attempt
        metrics <- testMeter.collectMetrics
      } yield {
        assert(
          result.isLeft,
          s"expected the raised error to propagate, got: $result"
        )
        MetricExpectations.checkAll(
          metrics,
          MetricExpectation
            .histogram("http.client.request.duration")
            .containsPoints(
              PointExpectation.histogram
                .attributesSubset(
                  Attribute("http.request.method", "GET"),
                  Attribute("error.type", "java.net.ConnectException")
                )
            )
        ) match {
          case Right(_)         => ()
          case Left(mismatches) => fail(MetricExpectations.format(mismatches))
        }
      }
    }
  }

  test("the request duration histogram uses LatencyBuckets.seconds") {
    Metrics.test[IO]("purerest-client-metrics-test").use { testMeter =>
      val wrapped =
        ClientMetrics.middleware(testMeter.meter)(Client.fromHttpApp(stubApp))
      for {
        _ <- wrapped.run(Request[IO](uri = uri"/ping")).use_
        metrics <- testMeter.collectMetrics
      } yield MetricExpectations.checkAll(
        metrics,
        MetricExpectation
          .histogram("http.client.request.duration")
          .containsPoints(
            PointExpectation.histogram.boundaries(LatencyBuckets.seconds)
          )
      ) match {
        case Right(_)         => ()
        case Left(mismatches) => fail(MetricExpectations.format(mismatches))
      }
    }
  }
}
