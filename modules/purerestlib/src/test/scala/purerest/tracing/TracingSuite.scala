package purerest.tracing

import cats.effect.IO
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.`export`.{
  BatchSpanProcessor,
  SimpleSpanProcessor
}
import munit.CatsEffectSuite
import org.typelevel.otel4s.oteljava.OtelJava

import scala.jdk.CollectionConverters._

class TracingSuite extends CatsEffectSuite {

  test("test tracer records a created span") {
    Tracing.test[IO]("purerest-test").use { testTracer =>
      for {
        _ <- testTracer.tracer.span("my-operation").use(_ => IO.unit)
        spans <- testTracer.finishedSpans
      } yield assertEquals(spans.map(_.getName), List("my-operation"))
    }
  }

  /** Runs one span through the SDK `Tracing.resource` builds, captured by an
    * in-memory exporter in place of the configured one.
    */
  private def exportedSpan(samplingRatio: SamplingRatio) = {
    val exporter = InMemorySpanExporter.create()
    OtelJava
      .resource[IO](
        IO(
          Tracing.sdk(
            "order-service",
            samplingRatio,
            SimpleSpanProcessor.create(exporter)
          )
        )
      )
      .evalMap(_.tracerProvider.get("purerest-test"))
      // Read before release: SDK shutdown resets the in-memory exporter.
      .use(
        _.span("my-operation").use(_ => IO.unit) >>
          IO(exporter.getFinishedSpanItems.asScala.toList)
      )
  }

  test("ratio 1 samples every root span") {
    exportedSpan(SamplingRatio.Always).map(spans =>
      assertEquals(spans.map(_.getName), List("my-operation"))
    )
  }

  test("ratio 0 samples no root span") {
    exportedSpan(SamplingRatio.Never).map(spans => assertEquals(spans, Nil))
  }

  test("spans carry service.name, so Tempo can group them by service") {
    exportedSpan(SamplingRatio.Always).map(spans =>
      assertEquals(
        spans.map(
          _.getResource.getAttribute(AttributeKey.stringKey("service.name"))
        ),
        List("order-service")
      )
    )
  }

  test("OTLP/HTTP signal path appended to the base endpoint, once") {
    assertEquals(
      Tracing.tracesUrl("http://tempo:4318"),
      "http://tempo:4318/v1/traces"
    )
    assertEquals(
      Tracing.tracesUrl("http://tempo:4318/"),
      "http://tempo:4318/v1/traces"
    )
    assertEquals(
      Tracing.tracesUrl("http://tempo:4318/v1/traces"),
      "http://tempo:4318/v1/traces"
    )
  }

  test("an OTLP endpoint selects a batch processor, none the logging one") {
    val otlp =
      Tracing.processor(
        TracingConfig(Some("http://localhost:4318"), SamplingRatio.Always)
      )
    val logging = Tracing.processor(TracingConfig.default)
    try {
      assert(otlp.isInstanceOf[BatchSpanProcessor], otlp.toString)
      assert(logging.isInstanceOf[SimpleSpanProcessor], logging.toString)
    } finally {
      otlp.shutdown().join(5, java.util.concurrent.TimeUnit.SECONDS)
      logging.shutdown()
    }
  }

  test("resource(…, default) still gives a working tracer") {
    Tracing
      .resource[IO]("purerest-test", TracingConfig.default)
      .use(_.span("my-operation").use(_ => IO.unit))
  }

  test("main runs the manual check on the env's config") {
    assume(
      !sys.env.contains(TracingConfig.EndpointVar),
      "OTLP endpoint set; covered by verify-perf5-otlp.sh"
    )
    IO.blocking(Tracing.main(Array.empty))
  }
}
