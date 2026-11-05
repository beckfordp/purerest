package purerest.tracing

import cats.effect.IO
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.{SpanKind, StatusCode}
import munit.CatsEffectSuite
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.{HttpRoutes, Request, Status}

class ServerTracingSuite extends CatsEffectSuite {

  private val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "ping" =>
      Ok("pong")
    case GET -> Root / "boom" =>
      InternalServerError()
  }

  test("wrapped routes record a span for a handled request") {
    Tracing.test[IO]("purerest-server-test").use { testTracer =>
      val wrapped = ServerTracing.middleware(testTracer.tracer)(routes)
      for {
        response <- wrapped.orNotFound.run(Request[IO](uri = uri"/ping"))
        spans <- testTracer.finishedSpans
      } yield {
        assertEquals(response.status, Status.Ok)
        assertEquals(spans.map(_.getName), List("GET /ping"))
      }
    }
  }

  test("wrapped routes still return 404 for unmatched paths") {
    Tracing.test[IO]("purerest-server-test").use { testTracer =>
      val wrapped = ServerTracing.middleware(testTracer.tracer)(routes)
      for {
        response <- wrapped.orNotFound.run(Request[IO](uri = uri"/nope"))
      } yield assertEquals(response.status, Status.NotFound)
    }
  }

  test("the span is Server-kind with HTTP server attributes") {
    Tracing.test[IO]("purerest-server-test").use { testTracer =>
      val wrapped = ServerTracing.middleware(testTracer.tracer)(routes)
      for {
        _ <- wrapped.orNotFound.run(Request[IO](uri = uri"/ping"))
        spans <- testTracer.finishedSpans
      } yield {
        assertEquals(spans.map(_.getKind), List(SpanKind.SERVER))
        val attrs = spans.head.getAttributes
        assertEquals(
          attrs.get(AttributeKey.stringKey("http.request.method")),
          "GET"
        )
        assertEquals(attrs.get(AttributeKey.stringKey("url.path")), "/ping")
        assertEquals(
          attrs.get(AttributeKey.longKey("http.response.status_code")),
          Long.box(200L)
        )
      }
    }
  }

  test("the span continues an inbound traceparent") {
    Tracing.test[IO]("purerest-server-test").use { testTracer =>
      val wrapped = ServerTracing.middleware(testTracer.tracer)(routes)
      val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
      val parentId = "00f067aa0ba902b7"
      for {
        _ <- wrapped.orNotFound.run(
          Request[IO](uri = uri"/ping").putHeaders(
            org.http4s.Header.Raw(
              org.typelevel.ci.CIString("traceparent"),
              s"00-$traceId-$parentId-01"
            )
          )
        )
        spans <- testTracer.finishedSpans
      } yield {
        assertEquals(spans.map(_.getTraceId), List(traceId))
        assertEquals(spans.map(_.getParentSpanId), List(parentId))
      }
    }
  }

  test("a 5xx response marks the span Error") {
    Tracing.test[IO]("purerest-server-test").use { testTracer =>
      val wrapped = ServerTracing.middleware(testTracer.tracer)(routes)
      for {
        _ <- wrapped.orNotFound.run(Request[IO](uri = uri"/boom"))
        spans <- testTracer.finishedSpans
      } yield assertEquals(
        spans.map(_.getStatus.getStatusCode),
        List(StatusCode.ERROR)
      )
    }
  }
}
