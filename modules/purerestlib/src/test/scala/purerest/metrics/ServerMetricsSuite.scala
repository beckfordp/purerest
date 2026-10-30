package purerest.metrics

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.{HttpRoutes, Request, Status, Uri}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.oteljava.testkit.metrics.{
  MetricExpectation,
  MetricExpectations,
  PointExpectation
}

class ServerMetricsSuite extends CatsEffectSuite {

  private val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "ping" =>
      Ok("pong")
  }

  test("routeTemplate collapses a UUID-shaped path segment to {id}") {
    assertEquals(
      ServerMetrics.routeTemplate(
        "/orders/550e8400-e29b-41d4-a716-446655440000"
      ),
      "/orders/{id}"
    )
  }

  test("routeTemplate leaves non-UUID segments untouched") {
    assertEquals(ServerMetrics.routeTemplate("/orders"), "/orders")
  }

  test(
    "wrapped routes record a UUID-shaped path segment as {id}, not the literal path"
  ) {
    val orderId = "550e8400-e29b-41d4-a716-446655440000"
    val ordersRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case GET -> Root / "orders" / _ => Ok("order")
    }
    Metrics.test[IO]("purerest-server-metrics-test").use { testMeter =>
      val wrapped = ServerMetrics.middleware(testMeter.meter)(ordersRoutes)
      for {
        _ <- wrapped.orNotFound.run(
          Request[IO](uri = Uri.unsafeFromString(s"/orders/$orderId"))
        )
        metrics <- testMeter.collectMetrics
      } yield MetricExpectations.checkAll(
        metrics,
        MetricExpectation
          .histogram("http.server.request.duration")
          .containsPoints(
            PointExpectation.histogram
              .attributesSubset(Attribute("http.route", "/orders/{id}"))
          )
      ) match {
        case Right(_)         => ()
        case Left(mismatches) => fail(MetricExpectations.format(mismatches))
      }
    }
  }

  test(
    "wrapped routes record a request duration measurement for a handled request"
  ) {
    Metrics.test[IO]("purerest-server-metrics-test").use { testMeter =>
      for {
        wrapped <- IO.pure(ServerMetrics.middleware(testMeter.meter)(routes))
        response <- wrapped.orNotFound.run(Request[IO](uri = uri"/ping"))
        metrics <- testMeter.collectMetrics
      } yield {
        assertEquals(response.status, Status.Ok)
        MetricExpectations.checkAll(
          metrics,
          MetricExpectation
            .histogram("http.server.request.duration")
            .containsPoints(
              PointExpectation.histogram
                .attributesSubset(
                  Attribute("http.request.method", "GET"),
                  Attribute("http.route", "/ping"),
                  Attribute("http.response.status_code", 200L)
                )
            )
        ) match {
          case Right(_)         => ()
          case Left(mismatches) => fail(MetricExpectations.format(mismatches))
        }
      }
    }
  }

  test(
    "reuses the same histogram instrument across multiple requests on the wrapped routes"
  ) {
    Metrics.test[IO]("purerest-server-metrics-test").use { testMeter =>
      val wrapped = ServerMetrics.middleware(testMeter.meter)(routes)
      for {
        _ <- wrapped.orNotFound.run(Request[IO](uri = uri"/ping"))
        _ <- wrapped.orNotFound.run(Request[IO](uri = uri"/ping"))
        metrics <- testMeter.collectMetrics
      } yield {
        val data = metrics.find(_.getName == "http.server.request.duration")
        assert(
          data.isDefined,
          s"expected an http.server.request.duration series, got: $metrics"
        )
        // Both requests share identical attributes, so a reused instrument
        // aggregates them into a single point (count=2) rather than one per call.
        val points = data.get.getHistogramData.getPoints
        assertEquals(points.size, 1)
        assertEquals(points.iterator.next.getCount, 2L)
      }
    }
  }

  test("wrapped routes still return 404 for unmatched paths") {
    Metrics.test[IO]("purerest-server-metrics-test").use { testMeter =>
      val wrapped = ServerMetrics.middleware(testMeter.meter)(routes)
      for {
        response <- wrapped.orNotFound.run(Request[IO](uri = uri"/nope"))
      } yield assertEquals(response.status, Status.NotFound)
    }
  }

  test(
    "a route that raises still records a duration measurement, tagged with error.type"
  ) {
    val failingRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case GET -> Root / "boom" =>
        IO.raiseError(new RuntimeException("boom"))
    }
    Metrics.test[IO]("purerest-server-metrics-test").use { testMeter =>
      val wrapped = ServerMetrics.middleware(testMeter.meter)(failingRoutes)
      for {
        result <- wrapped.orNotFound.run(Request[IO](uri = uri"/boom")).attempt
        metrics <- testMeter.collectMetrics
      } yield {
        assert(
          result.isLeft,
          s"expected the raised error to propagate, got: $result"
        )
        MetricExpectations.checkAll(
          metrics,
          MetricExpectation
            .histogram("http.server.request.duration")
            .containsPoints(
              PointExpectation.histogram
                .attributesSubset(
                  Attribute("http.request.method", "GET"),
                  Attribute("http.route", "/boom"),
                  Attribute("error.type", "java.lang.RuntimeException")
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
    Metrics.test[IO]("purerest-server-metrics-test").use { testMeter =>
      val wrapped = ServerMetrics.middleware(testMeter.meter)(routes)
      for {
        _ <- wrapped.orNotFound.run(Request[IO](uri = uri"/ping"))
        metrics <- testMeter.collectMetrics
      } yield MetricExpectations.checkAll(
        metrics,
        MetricExpectation
          .histogram("http.server.request.duration")
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
