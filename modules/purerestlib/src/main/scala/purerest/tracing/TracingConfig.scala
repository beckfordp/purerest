package purerest.tracing

import cats.syntax.all._

/** Config for [[Tracing.resource]]: where spans go and how many are kept.
  *
  * @param otlpEndpoint
  *   OTLP gRPC endpoint (e.g. Tempo's `http://tempo:4317`); `None` logs spans
  *   to stdout instead, so a service still runs without a trace backend.
  * @param samplingRatio
  *   fraction of new (root) traces sampled, in [0, 1]; child spans follow the
  *   parent's decision.
  */
final case class TracingConfig(
    otlpEndpoint: Option[String],
    samplingRatio: Double
)

object TracingConfig {

  /** Logging exporter, every trace sampled. */
  val default: TracingConfig = TracingConfig(None, 1.0)

  val EndpointVar = "OTEL_EXPORTER_OTLP_ENDPOINT"
  val SamplerArgVar = "OTEL_TRACES_SAMPLER_ARG"

  /** Reads the standard OTel env vars ([[EndpointVar]], [[SamplerArgVar]]). A
    * blank endpoint counts as unset; a ratio that isn't a number in [0, 1] is
    * an error rather than silently defaulted.
    */
  def fromEnv(env: Map[String, String]): Either[String, TracingConfig] = {
    val endpoint = env.get(EndpointVar).map(_.trim).filter(_.nonEmpty)
    env
      .get(SamplerArgVar)
      .traverse(parseRatio)
      .map(ratio =>
        TracingConfig(endpoint, ratio.getOrElse(default.samplingRatio))
      )
  }

  private def parseRatio(raw: String): Either[String, Double] =
    raw.trim.toDoubleOption
      .filter(r => r >= 0.0 && r <= 1.0)
      .toRight(s"$SamplerArgVar must be a number in [0, 1], got '$raw'")
}
