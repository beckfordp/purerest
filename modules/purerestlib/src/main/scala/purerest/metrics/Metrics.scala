package purerest.metrics

import cats.effect.{Async, Resource}
import cats.syntax.all._
import cats.effect.unsafe.metrics.{IORuntimeMetrics => CatsIORuntimeMetrics}
import io.opentelemetry.exporter.prometheus.PrometheusHttpServer
import io.opentelemetry.instrumentation.runtimemetrics.java17.RuntimeMetrics
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.MetricData
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.oteljava.context.LocalContextProvider
import org.typelevel.otel4s.oteljava.testkit.metrics.MetricsTestkit
import org.typelevel.otel4s.instrumentation.ce.IORuntimeMetrics
import org.typelevel.otel4s.metrics.{Meter, MeterProvider}

/** Meter construction for purerest's Prometheus/OTel-based metrics. */
object Metrics {

  /** A meter backed by an in-memory metric reader, exposing collected metrics —
    * for asserting on metrics behavior in tests.
    */
  final case class TestMeter[F[_]](
      meter: Meter[F],
      collectMetrics: F[List[MetricData]]
  )

  /** A meter that exposes collected metrics on a Prometheus scrape endpoint
    * (`GET /metrics` on `port`), for manual verification and real scraping when
    * running a service locally. No OTLP/collector export is configured.
    *
    * Also registers JVM runtime metrics (heap, GC, threads, CPU) and, when
    * `ioRuntimeMetrics` is given (`IOApp`'s `runtime.metrics`), cats-effect
    * runtime metrics — see [[runtimeMetrics]].
    */
  def oteljava[F[_]: {Async, LocalContextProvider}](
      instrumentationName: String,
      port: Int,
      ioRuntimeMetrics: Option[CatsIORuntimeMetrics] = None
  ): Resource[F, Meter[F]] =
    OtelJava
      .resource[F](
        Async[F].delay {
          val meterProvider = SdkMeterProvider
            .builder()
            .registerMetricReader(
              // Explicit "0.0.0.0": without it, the underlying Prometheus
              // exporter binds loopback-only, which is invisible to a
              // scraper running in a different container (or on a different
              // host) even though `curl localhost:<port>/metrics` from
              // *inside* the same container/process works fine — the gap
              // this track's real docker-compose network setup caught,
              // where curl-from-the-host-while-the-service-also-ran-on-the-
              // host (every prior verify-*.sh script) couldn't.
              PrometheusHttpServer
                .builder()
                .setHost("0.0.0.0")
                .setPort(port)
                .build()
            )
            .build()
          OpenTelemetrySdk
            .builder()
            .setMeterProvider(meterProvider)
            .build()
        }
      )
      .flatTap(runtimeMetrics[F](_, ioRuntimeMetrics))
      .evalMap(_.meterProvider.get(instrumentationName))

  /** Registers OTel JVM runtime telemetry (`jvm.*`) on `otel`'s SDK and, if
    * given, otel4s cats-effect runtime metrics (`cats.effect.runtime.*`:
    * compute pool, worker threads, CPU starvation) — gluon PERF-4.1. Both are
    * unregistered when the resource is released.
    *
    * JMX metrics only (heap, GC, threads, CPU, classes): every JFR feature is
    * disabled, because JFR streaming throttled and OOM-killed 500m/384Mi
    * service pods at rest (gluon TD-28).
    */
  def runtimeMetrics[F[_]: Async](
      otel: OtelJava[F],
      ioRuntimeMetrics: Option[CatsIORuntimeMetrics]
  ): Resource[F, Unit] = {
    given MeterProvider[F] = otel.meterProvider
    Resource
      .fromAutoCloseable(
        Async[F].delay(
          RuntimeMetrics.builder(otel.underlying).disableAllFeatures().build()
        )
      )
      .void >>
      ioRuntimeMetrics.traverse_(
        IORuntimeMetrics.register[F](_, IORuntimeMetrics.Config.default)
      )
  }

  /** A meter backed by an in-memory metric reader, for asserting on collected
    * metrics in tests.
    */
  def test[F[_]: {Async, LocalContextProvider}](
      instrumentationName: String
  ): Resource[F, TestMeter[F]] =
    MetricsTestkit
      .inMemory[F]()
      .evalMap { testkit =>
        testkit.meterProvider
          .get(instrumentationName)
          .map(TestMeter(_, testkit.collectMetrics))
      }
}
