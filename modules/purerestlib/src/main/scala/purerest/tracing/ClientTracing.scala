package purerest.tracing

import cats.effect.{Concurrent, Resource}
import cats.syntax.all._
import org.http4s.{Header, Headers, Status}
import org.http4s.client.Client
import org.typelevel.ci.CIString
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.trace.{SpanKind, StatusCode, Tracer}

/** Client-side span + trace-context propagation middleware for http4s. */
object ClientTracing {

  /** Wraps a `Client[F]` so that, if a span is active, each outgoing request
    * runs in a `Client`-kind child span named `<METHOD> <host>` (OpenTelemetry
    * HTTP client attributes; a 5xx or a raised error marks it Error) and
    * carries that span's W3C trace context in its headers, so the callee's
    * `ServerTracing` span joins the caller's trace. The span ends when the
    * response is released. A no-op passthrough when no span is active.
    */
  def middleware[F[_]: Concurrent](
      tracer: Tracer[F]
  )(client: Client[F]): Client[F] =
    Client[F] { req =>
      Resource.eval(tracer.currentSpanContext).flatMap {
        case None    => client.run(req)
        case Some(_) =>
          val host = req.uri.host.fold("")(_.value)
          tracer
            .spanBuilder(s"${req.method.name} $host".trim)
            .withSpanKind(SpanKind.Client)
            .addAttributes(
              Attribute("http.request.method", req.method.name),
              Attribute("server.address", host),
              Attribute("url.path", req.uri.path.renderString)
            )
            .build
            .resource
            .flatMap { res =>
              for {
                carrier <- Resource.eval(
                  res.trace(tracer.propagate(Map.empty[String, String]))
                )
                traceHeaders = carrier.toList.map { case (k, v) =>
                  Header.Raw(CIString(k), v)
                }
                response <- client
                  .run(req.withHeaders(req.headers ++ Headers(traceHeaders)))
                  .onError { case e =>
                    Resource.eval(
                      res.span.recordException(e) *>
                        res.span.setStatus(StatusCode.Error)
                    )
                  }
                _ <- Resource.eval(
                  res.span.addAttribute(
                    Attribute(
                      "http.response.status_code",
                      response.status.code.toLong
                    )
                  ) *> res.span
                    .setStatus(StatusCode.Error)
                    .whenA(response.status.responseClass == Status.ServerError)
                )
              } yield response
            }
      }
    }
}
