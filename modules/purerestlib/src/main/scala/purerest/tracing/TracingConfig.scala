// SPDX-License-Identifier: MIT

package purerest.tracing

import cats.syntax.all._

/** Config for [[Tracing.resource]]: where spans go and how many are kept.
  *
  * @param otlpEndpoint
  *   OTLP/HTTP base endpoint (e.g. Tempo's `http://tempo:4318`); `None` logs
  *   spans to stdout instead, so a service still runs without a trace backend.
  * @param samplingRatio
  *   fraction of new (root) traces sampled, in [0, 1]; child spans follow the
  *   parent's decision.
  */
final case class TracingConfig(
    otlpEndpoint: Option[String],
    samplingRatio: SamplingRatio
)

/** Fraction of root traces sampled: a `Double` in [0, 1], checked once here
  * (the OTel SDK's ratio sampler throws on anything else).
  */
opaque type SamplingRatio = Double

object SamplingRatio {
  val Always: SamplingRatio = 1.0
  val Never: SamplingRatio = 0.0

  def from(ratio: Double): Either[String, SamplingRatio] =
    Either.cond(
      ratio >= 0.0 && ratio <= 1.0,
      ratio,
      s"sampling ratio must be in [0, 1], got $ratio"
    )

  extension (ratio: SamplingRatio) def value: Double = ratio
}

object TracingConfig {

  /** Logging exporter, every trace sampled. */
  val default: TracingConfig = TracingConfig(None, SamplingRatio.Always)

  val EndpointVar = "OTEL_EXPORTER_OTLP_ENDPOINT"
  val SamplerArgVar = "OTEL_TRACES_SAMPLER_ARG"

  /** Reads the standard OTel env vars ([[EndpointVar]], [[SamplerArgVar]]).
    * Blank counts as unset (as in the OTel spec); a ratio that isn't a number
    * in [0, 1] is an error rather than silently defaulted.
    */
  def fromEnv(env: Map[String, String]): Either[String, TracingConfig] = {
    def nonBlank(name: String) = env.get(name).map(_.trim).filter(_.nonEmpty)
    nonBlank(SamplerArgVar)
      .traverse(parseRatio)
      .map(ratio =>
        TracingConfig(
          nonBlank(EndpointVar),
          ratio.getOrElse(SamplingRatio.Always)
        )
      )
  }

  private def parseRatio(raw: String): Either[String, SamplingRatio] =
    raw.toDoubleOption
      .toRight(s"$SamplerArgVar must be a number, got '$raw'")
      .flatMap(SamplingRatio.from(_).leftMap(e => s"$SamplerArgVar: $e"))
}
