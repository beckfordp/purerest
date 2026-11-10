// SPDX-License-Identifier: MIT

package purerest.tracing

import cats.effect.{Async, IO, Resource}
import cats.syntax.all._
import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.exporter.logging.LoggingSpanExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.{Resource => SdkResource}
import io.opentelemetry.sdk.trace.{SdkTracerProvider, SpanProcessor}
import io.opentelemetry.sdk.trace.`export`.{
  BatchSpanProcessor,
  SimpleSpanProcessor
}
import io.opentelemetry.sdk.trace.samplers.Sampler
import io.opentelemetry.sdk.trace.data.SpanData
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.oteljava.context.LocalContextProvider
import org.typelevel.otel4s.oteljava.testkit.trace.TracesTestkit
import org.typelevel.otel4s.trace.Tracer

/** Tracer construction for purerest's OpenTelemetry-based tracing. */
object Tracing {

  /** A tracer backed by an in-memory span exporter, exposing captured spans —
    * for asserting on tracing behavior in tests.
    */
  final case class TestTracer[F[_]](
      tracer: Tracer[F],
      finishedSpans: F[List[SpanData]]
  )

  /** A tracer exporting spans per `config`: over OTLP/HTTP (batched) when
    * `config.otlpEndpoint` is set (gluon ADR 0021: Tempo), otherwise to the
    * console (stdout), so a service still runs without a trace backend. Root
    * spans are sampled at `config.samplingRatio`; child spans follow their
    * parent's decision. Spans carry `service.name = serviceName`. Releasing the
    * resource shuts the SDK down, flushing any batched spans.
    */
  def resource[F[_]: {Async, LocalContextProvider}](
      serviceName: String,
      config: TracingConfig
  ): Resource[F, Tracer[F]] =
    OtelJava
      .resource[F](
        Async[F].delay(
          sdk(serviceName, config.samplingRatio, processor(config))
        )
      )
      .evalMap(_.tracerProvider.get(serviceName))

  /** A tracer that exports spans to the console (stdout), for manual
    * verification when running a service locally: [[resource]] with
    * [[TracingConfig.default]].
    */
  def console[F[_]: {Async, LocalContextProvider}](
      instrumentationName: String
  ): Resource[F, Tracer[F]] =
    resource[F](instrumentationName, TracingConfig.default)

  /** The span processor for `config`'s exporter. */
  private[tracing] def processor(config: TracingConfig): SpanProcessor =
    config.otlpEndpoint match {
      case Some(endpoint) =>
        BatchSpanProcessor
          .builder(
            OtlpHttpSpanExporter
              .builder()
              .setEndpoint(tracesUrl(endpoint))
              .build()
          )
          .build()
      case None => SimpleSpanProcessor.create(LoggingSpanExporter.create())
    }

  /** `endpoint` as the OTLP/HTTP traces URL. Like `OTEL_EXPORTER_OTLP_ENDPOINT`
    * in the OTel spec it's a base URL, so `/v1/traces` is appended (unless
    * already there); the exporter builder itself takes the full URL. HTTP, not
    * gRPC: the JDK HttpClient sender has no gRPC support.
    */
  private[tracing] def tracesUrl(endpoint: String): String = {
    val base = endpoint.stripSuffix("/")
    if (base.endsWith("/v1/traces")) base else s"$base/v1/traces"
  }

  /** The SDK behind [[resource]], with the span processor passed in so tests
    * can capture spans in memory.
    */
  private[tracing] def sdk(
      serviceName: String,
      samplingRatio: SamplingRatio,
      processor: SpanProcessor
  ): OpenTelemetrySdk = {
    val tracerProvider = SdkTracerProvider
      .builder()
      .setResource(
        SdkResource
          .getDefault()
          .merge(
            SdkResource.create(
              Attributes.of(AttributeKey.stringKey("service.name"), serviceName)
            )
          )
      )
      .setSampler(
        Sampler.parentBased(Sampler.traceIdRatioBased(samplingRatio.value))
      )
      .addSpanProcessor(processor)
      .build()
    OpenTelemetrySdk
      .builder()
      .setTracerProvider(tracerProvider)
      // W3C Trace Context propagator — without this, Tracer.propagate/joinOrRoot
      // are no-ops, since OpenTelemetrySdkBuilder defaults to no propagators.
      .setPropagators(
        ContextPropagators.create(W3CTraceContextPropagator.getInstance())
      )
      .build()
  }

  /** A tracer backed by an in-memory span exporter, for asserting on captured
    * spans in tests.
    */
  def test[F[_]: {Async, LocalContextProvider}](
      instrumentationName: String
  ): Resource[F, TestTracer[F]] =
    // W3CTraceContextPropagator registered explicitly — TracesTestkit.inMemory
    // defaults to no propagators, which would make Tracer.propagate/joinOrRoot no-ops.
    TracesTestkit
      .inMemory[F](
        _.addTextMapPropagators(W3CTraceContextPropagator.getInstance())
      )
      .evalMap { testkit =>
        testkit.tracerProvider
          .get(instrumentationName)
          .map(TestTracer(_, testkit.finishedSpans))
      }

  /** Manual check: run via `sbt "purerestlib/runMain purerest.tracing.Tracing"`
    * and confirm a LoggingSpanExporter log line is printed for "demo-span";
    * with `OTEL_EXPORTER_OTLP_ENDPOINT` set, the span goes to that collector
    * instead (`scripts/verify-perf5-otlp.sh`).
    */
  def main(args: Array[String]): Unit = {
    given cats.effect.unsafe.IORuntime = cats.effect.unsafe.implicits.global
    IO.fromEither(
      TracingConfig.fromEnv(sys.env).leftMap(new IllegalArgumentException(_))
    ).flatMap(
      resource[IO]("manual-check", _)
        .use(_.span("demo-span").use(_ => IO.unit))
    ).unsafeRunSync()
  }
}
