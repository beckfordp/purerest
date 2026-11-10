// SPDX-License-Identifier: MIT

package purerest.tracing

import munit.FunSuite

class TracingConfigSuite extends FunSuite {

  private val endpoint = "http://tempo.observability:4318"

  test("no env vars → logging exporter, sample everything") {
    assertEquals(TracingConfig.fromEnv(Map.empty), Right(TracingConfig.default))
    assertEquals(
      TracingConfig.default,
      TracingConfig(None, SamplingRatio.Always)
    )
  }

  test("OTEL_EXPORTER_OTLP_ENDPOINT sets the OTLP endpoint") {
    assertEquals(
      TracingConfig.fromEnv(Map("OTEL_EXPORTER_OTLP_ENDPOINT" -> endpoint)),
      Right(TracingConfig(Some(endpoint), SamplingRatio.Always))
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
        TracingConfig
          .fromEnv(Map("OTEL_TRACES_SAMPLER_ARG" -> ratio.toString))
          .map(_.samplingRatio.value),
        Right(ratio)
      )
    }
  }

  test("a blank ratio is treated as unset") {
    assertEquals(
      TracingConfig.fromEnv(Map("OTEL_TRACES_SAMPLER_ARG" -> " ")),
      Right(TracingConfig.default)
    )
  }

  test("a non-numeric or out-of-range ratio is an error, not a default") {
    List("abc", "-0.1", "1.5", "NaN").foreach { arg =>
      assert(
        TracingConfig.fromEnv(Map("OTEL_TRACES_SAMPLER_ARG" -> arg)).isLeft,
        s"expected Left for '$arg'"
      )
    }
  }

  test("SamplingRatio.from rejects values outside [0, 1]") {
    assert(SamplingRatio.from(1.01).isLeft)
    assert(SamplingRatio.from(-0.01).isLeft)
    assertEquals(SamplingRatio.from(0.25).map(_.value), Right(0.25))
  }
}
