package purekafka.tracing

import cats.effect.IO
import fs2.kafka.Headers
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import munit.CatsEffectSuite
import org.typelevel.otel4s.oteljava.testkit.trace.TracesTestkit

class KafkaTracingSuite extends CatsEffectSuite {

  // Local test-tracer setup, deliberately not importing purerestlib's own
  // `purerest.tracing.Tracing.test` helper — this module has no dependency
  // on purerestlib (ADR 0010), so it builds the same shape itself.
  private def testTracer =
    TracesTestkit.inMemory[IO](
      _.addTextMapPropagators(W3CTraceContextPropagator.getInstance())
    )

  test(
    "injectHeaders writes a traceparent header when a span is active"
  ) {
    testTracer.use { testkit =>
      for {
        tracer <- testkit.tracerProvider.get("kafka-tracing-test")
        headers <- tracer.span("produce-span").use { _ =>
          KafkaTracing.injectHeaders(tracer)(Headers.empty)
        }
      } yield assert(
        headers.exists("traceparent"),
        s"expected a traceparent header, got: $headers"
      )
    }
  }

  test("injectHeaders is a no-op when no span is active") {
    testTracer.use { testkit =>
      for {
        tracer <- testkit.tracerProvider.get("kafka-tracing-test")
        headers <- KafkaTracing.injectHeaders(tracer)(Headers.empty)
      } yield assert(
        !headers.exists("traceparent"),
        s"expected no traceparent header, got: $headers"
      )
    }
  }

  test(
    "a consumer span created via traced() is a child of the producer span whose headers it reads"
  ) {
    testTracer.use { testkit =>
      for {
        tracer <- testkit.tracerProvider.get("kafka-tracing-test")
        headers <- tracer.span("produce-span").use { _ =>
          KafkaTracing.injectHeaders(tracer)(Headers.empty)
        }
        _ <- KafkaTracing.traced(tracer)(headers, "consume-span", "my-topic")(
          IO.unit
        )
        spans <- testkit.finishedSpans
      } yield {
        val byName = spans.map(s => s.getName -> s).toMap
        val producer = byName("produce-span")
        val consumer = byName("consume-span")
        assertEquals(
          consumer.getParentSpanContext.getSpanId,
          producer.getSpanContext.getSpanId
        )
      }
    }
  }

  test("traced() tags the span with messaging semantic attributes") {
    testTracer.use { testkit =>
      for {
        tracer <- testkit.tracerProvider.get("kafka-tracing-test")
        _ <- KafkaTracing.traced(tracer)(
          Headers.empty,
          "consume-span",
          "my-topic"
        )(IO.unit)
        spans <- testkit.finishedSpans
      } yield {
        val attrs = spans.head.getAttributes
        assertEquals(
          attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey(
            "messaging.system"
          )),
          "kafka"
        )
        assertEquals(
          attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey(
            "messaging.destination.name"
          )),
          "my-topic"
        )
      }
    }
  }
}
