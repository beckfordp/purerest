package purerest.metrics

import cats.effect.IO
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import munit.CatsEffectSuite
import org.typelevel.otel4s.oteljava.OtelJava

import scala.jdk.CollectionConverters._

class RuntimeMetricsSuite extends CatsEffectSuite {

  private def collectNames(
      ioRuntimeMetrics: Option[cats.effect.unsafe.metrics.IORuntimeMetrics]
  ): IO[Set[String]] = {
    val reader = InMemoryMetricReader.create()
    OtelJava
      .resource[IO](IO {
        OpenTelemetrySdk
          .builder()
          .setMeterProvider(
            SdkMeterProvider.builder().registerMetricReader(reader).build()
          )
          .build()
      })
      .flatMap(Metrics.runtimeMetrics[IO](_, ioRuntimeMetrics))
      .use(_ => IO(reader.collectAllMetrics().asScala.map(_.getName).toSet))
  }

  test("registers JVM runtime metrics on the SDK") {
    collectNames(None).map { names =>
      assert(names.contains("jvm.memory.used"), s"got: $names")
      assert(names.contains("jvm.thread.count"), s"got: $names")
      assert(!names.exists(_.startsWith("cats.effect")), s"got: $names")
    }
  }

  test("registers cats-effect IORuntime metrics when given") {
    collectNames(Some(munitIORuntime.metrics)).map { names =>
      assert(
        names.exists(_.startsWith("cats.effect.runtime")),
        s"got: $names"
      )
    }
  }
}
