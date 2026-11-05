package purerest.tracing

import cats.effect.{IO, Ref}
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.{SpanKind, StatusCode}
import munit.CatsEffectSuite
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.{HttpApp, Request}

class ClientTracingSuite extends CatsEffectSuite {

  test(
    "wrapped client injects the active span's trace context into outgoing headers"
  ) {
    Tracing.test[IO]("purerest-client-test").use { testTracer =>
      for {
        receivedHeaders <- Ref.of[IO, Map[String, String]](Map.empty)
        stubApp: HttpApp[IO] = HttpApp { req =>
          receivedHeaders.set(
            req.headers.headers.map(h => h.name.toString -> h.value).toMap
          ) *>
            Ok("pong")
        }
        client = Client.fromHttpApp(stubApp)
        wrapped = ClientTracing.middleware(testTracer.tracer)(client)
        _ <- testTracer.tracer.span("outbound-call").use { _ =>
          wrapped.run(Request[IO](uri = uri"/ping")).use_
        }
        headers <- receivedHeaders.get
      } yield assert(
        headers.contains("traceparent"),
        s"expected a traceparent header, got: $headers"
      )
    }
  }

  test("wrapped client is a no-op passthrough when no span is active") {
    Tracing.test[IO]("purerest-client-test").use { testTracer =>
      for {
        receivedHeaders <- Ref.of[IO, Map[String, String]](Map.empty)
        stubApp: HttpApp[IO] = HttpApp { req =>
          receivedHeaders.set(
            req.headers.headers.map(h => h.name.toString -> h.value).toMap
          ) *>
            Ok("pong")
        }
        client = Client.fromHttpApp(stubApp)
        wrapped = ClientTracing.middleware(testTracer.tracer)(client)
        _ <- wrapped.run(Request[IO](uri = uri"/ping")).use_
        headers <- receivedHeaders.get
      } yield assert(
        !headers.contains("traceparent"),
        s"expected no traceparent header, got: $headers"
      )
    }
  }

  test(
    "wrapped client records a Client-kind child span whose context is the one sent"
  ) {
    Tracing.test[IO]("purerest-client-test").use { testTracer =>
      for {
        receivedHeaders <- Ref.of[IO, Map[String, String]](Map.empty)
        stubApp: HttpApp[IO] = HttpApp { req =>
          receivedHeaders.set(
            req.headers.headers.map(h => h.name.toString -> h.value).toMap
          ) *> Ok("pong")
        }
        wrapped = ClientTracing.middleware(testTracer.tracer)(
          Client.fromHttpApp(stubApp)
        )
        _ <- testTracer.tracer.span("outbound-call").use { _ =>
          wrapped
            .run(Request[IO](uri = uri"http://inventory-service:8080/ping"))
            .use_
        }
        headers <- receivedHeaders.get
        spans <- testTracer.finishedSpans
      } yield {
        val client = spans.find(_.getKind == SpanKind.CLIENT).get
        val parent = spans.find(_.getName == "outbound-call").get
        assertEquals(client.getName, "GET inventory-service")
        assertEquals(client.getParentSpanId, parent.getSpanId)
        assertEquals(
          headers.get("traceparent").map(_.split("-")(2)),
          Some(client.getSpanId)
        )
        val attrs = client.getAttributes
        assertEquals(
          attrs.get(AttributeKey.stringKey("http.request.method")),
          "GET"
        )
        assertEquals(
          attrs.get(AttributeKey.stringKey("server.address")),
          "inventory-service"
        )
        assertEquals(attrs.get(AttributeKey.stringKey("url.path")), "/ping")
        assertEquals(
          attrs.get(AttributeKey.longKey("http.response.status_code")),
          Long.box(200L)
        )
        assertEquals(client.getStatus.getStatusCode, StatusCode.UNSET)
      }
    }
  }

  test("wrapped client marks the span Error on a 5xx response") {
    Tracing.test[IO]("purerest-client-test").use { testTracer =>
      val wrapped = ClientTracing.middleware(testTracer.tracer)(
        Client.fromHttpApp(HttpApp[IO](_ => InternalServerError()))
      )
      for {
        _ <- testTracer.tracer.span("outbound-call").use { _ =>
          wrapped.run(Request[IO](uri = uri"http://payment/x")).use_
        }
        spans <- testTracer.finishedSpans
      } yield assertEquals(
        spans.find(_.getKind == SpanKind.CLIENT).map(_.getStatus.getStatusCode),
        Some(StatusCode.ERROR)
      )
    }
  }

  test("wrapped client marks the span Error when the call raises") {
    Tracing.test[IO]("purerest-client-test").use { testTracer =>
      val wrapped = ClientTracing.middleware(testTracer.tracer)(
        Client.fromHttpApp(
          HttpApp[IO](_ => IO.raiseError(new RuntimeException("refused")))
        )
      )
      for {
        result <- testTracer.tracer.span("outbound-call").use { _ =>
          wrapped.run(Request[IO](uri = uri"http://payment/x")).use_.attempt
        }
        spans <- testTracer.finishedSpans
      } yield {
        assert(result.isLeft)
        assertEquals(
          spans
            .find(_.getKind == SpanKind.CLIENT)
            .map(_.getStatus.getStatusCode),
          Some(StatusCode.ERROR)
        )
      }
    }
  }

  test("wrapped client records no span when no span is active") {
    Tracing.test[IO]("purerest-client-test").use { testTracer =>
      val wrapped = ClientTracing.middleware(testTracer.tracer)(
        Client.fromHttpApp(HttpApp[IO](_ => Ok("pong")))
      )
      for {
        _ <- wrapped.run(Request[IO](uri = uri"/ping")).use_
        spans <- testTracer.finishedSpans
      } yield assertEquals(spans, Nil)
    }
  }
}
