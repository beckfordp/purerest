package purekafka.tracing

import cats.effect.Concurrent
import cats.syntax.all._
import fs2.kafka.{Header, HeaderDeserializer, HeaderSerializer, Headers}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.trace.Tracer

import java.nio.charset.StandardCharsets

/** Produce/consume trace-context propagation across the Kafka boundary — the
  * same W3C-context propagation `purerest.tracing.ClientTracing`/
  * `ServerTracing` already give HTTP, adapted to Kafka message headers instead
  * of HTTP headers. Uses `Tracer[F]`'s own `propagate`/`joinOrRoot` (an
  * implicit `TextMapGetter`/`TextMapUpdater[Map[String, String]]` already
  * resolves for a plain string-to-string carrier, same as the HTTP versions
  * rely on) rather than hand-rolling a `TextMapPropagator` — only the carrier's
  * representation (Kafka `Headers`, UTF-8 byte values, instead of HTTP
  * `Headers`) is actually new here.
  */
object KafkaTracing {

  private val charset = StandardCharsets.UTF_8
  private val stringSerializer: HeaderSerializer[String] =
    HeaderSerializer.string(charset)
  private val stringDeserializer: HeaderDeserializer[String] =
    HeaderDeserializer.string(charset)

  /** Injects the active span's W3C trace context into `headers`, if a span is
    * active — a no-op passthrough (returns `headers` unchanged) otherwise.
    * Intended use: before producing, append the result to a `ProducerRecord`'s
    * existing headers via `record.withHeaders(record.headers.concat(result))`.
    */
  def injectHeaders[F[_]: Concurrent](
      tracer: Tracer[F]
  )(headers: Headers): F[Headers] =
    tracer.propagate(Map.empty[String, String]).map { carrier =>
      carrier.foldLeft(headers) { case (acc, (key, value)) =>
        acc.append(key, value)(using stringSerializer)
      }
    }

  /** Reads back whatever W3C trace-context carrier [[injectHeaders]] wrote,
    * skipping any header this deserializer can't decode as a UTF-8 string
    * (non-tracing headers a caller's own code may have set).
    */
  private def extractCarrier(headers: Headers): Map[String, String] =
    headers.toChain.foldLeft(Map.empty[String, String]) { (acc, header) =>
      scala.util.Try(header.as(using stringDeserializer)) match {
        case scala.util.Success(value) => acc + (header.key -> value)
        case scala.util.Failure(_)     => acc
      }
    }

  /** Runs `fa` inside a span named `spanName`, joining the trace carried in
    * `headers` if one is present (otherwise starting a new root trace) — the
    * Kafka-consume-side mirror of `ServerTracing.middleware`'s inbound-request
    * handling. Tags the span with OpenTelemetry's messaging semantic convention
    * attributes (`messaging.system`/`messaging.destination.name`).
    */
  def traced[F[_]: Concurrent, A](
      tracer: Tracer[F]
  )(headers: Headers, spanName: String, topic: String)(fa: F[A]): F[A] = {
    val carrier = extractCarrier(headers)
    tracer.joinOrRoot(carrier) {
      tracer
        .spanBuilder(spanName)
        .addAttributes(
          Attribute("messaging.system", "kafka"),
          Attribute("messaging.destination.name", topic)
        )
        .build
        .use(_ => fa)
    }
  }
}
