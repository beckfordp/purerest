package purerest.tracing

import munit.FunSuite

class TracingConfigSuite extends FunSuite {

  private val endpoint = "http://tempo.observability:4317"

  test("no env vars → logging exporter, sample everything") {
    assertEquals(TracingConfig.fromEnv(Map.empty), Right(TracingConfig.default))
    assertEquals(TracingConfig.default, TracingConfig(None, 1.0))
  }

  test("OTEL_EXPORTER_OTLP_ENDPOINT sets the OTLP endpoint") {
    assertEquals(
      TracingConfig.fromEnv(Map("OTEL_EXPORTER_OTLP_ENDPOINT" -> endpoint)),
      Right(TracingConfig(Some(endpoint), 1.0))
    )
  }

  test("a blank endpoint is treated as unset") {
    assertEquals(
      TracingConfig.fromEnv(Map("OTEL_EXPORTER_OTLP_ENDPOINT" -> "  ")),
      Right(TracingConfig.default)
    )
  }

  test("OTEL_TRACES_SAMPLER_ARG sets the sampling ratio, bounds inclusive") {
    List(0.0, 0.5, 1.0).foreach { ratio =>
      assertEquals(
        TracingConfig.fromEnv(Map("OTEL_TRACES_SAMPLER_ARG" -> ratio.toString)),
        Right(TracingConfig(None, ratio))
      )
    }
  }

  test("a non-numeric or out-of-range ratio is an error, not a default") {
    List("abc", "", "-0.1", "1.5", "NaN").foreach { arg =>
      assert(
        TracingConfig.fromEnv(Map("OTEL_TRACES_SAMPLER_ARG" -> arg)).isLeft,
        s"expected Left for '$arg'"
      )
    }
  }
}
