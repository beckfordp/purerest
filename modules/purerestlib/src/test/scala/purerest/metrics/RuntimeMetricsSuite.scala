package purerest.metrics

import cats.effect.IO
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import munit.CatsEffectSuite
import org.typelevel.otel4s.oteljava.OtelJava

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class RuntimeMetricsSuite extends CatsEffectSuite {

  private def collectNames(
      ioRuntimeMetrics: Option[cats.effect.unsafe.metrics.IORuntimeMetrics],
      whileRunning: IO[Unit] = IO.unit
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
      .use(_ =>
        whileRunning >> IO(
          reader.collectAllMetrics().asScala.map(_.getName).toSet
        )
      )
  }

  test("registers JVM runtime metrics on the SDK") {
    collectNames(None).map { names =>
      assert(names.contains("jvm.memory.used"), s"got: $names")
      assert(names.contains("jvm.thread.count"), s"got: $names")
      assert(!names.exists(_.startsWith("cats.effect")), s"got: $names")
    }
  }

  // TD-28: JFR streaming costs CPU and off-heap memory on 500m/384Mi pods.
  // Allocate and outlast JFR's ~1s flush so its metrics would show if enabled.
  test("emits no JFR-only metrics (JMX only)") {
    val churn =
      IO(List.fill(200_000)(new Array[Byte](64)).size).void >> IO.sleep(
        3.seconds
      )
    collectNames(None, churn).map { names =>
      val jfrOnly = Set(
        "jvm.memory.allocation",
        "jvm.network.io",
        "jvm.network.time",
        "jvm.cpu.context_switch",
        "jvm.cpu.longlock"
      )
      assert((names & jfrOnly).isEmpty, s"JFR metrics: ${names & jfrOnly}")
      assert(
        names.contains("jvm.gc.duration") || names.contains("jvm.cpu.time"),
        s"got: $names"
      )
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
