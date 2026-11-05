package purerest.tracing

import cats.data.OptionT
import cats.effect.Concurrent
import cats.syntax.all._
import org.http4s.{HttpRoutes, Status}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.trace.{SpanKind, StatusCode, Tracer}

/** Server-side span-per-request middleware for http4s. */
object ServerTracing {

  /** Wraps `HttpRoutes[F]` with a `Server`-kind span per handled request
    * (OpenTelemetry HTTP server attributes; a 5xx marks it Error). If the
    * inbound request carries a W3C trace context in its headers, the span
    * continues that trace; otherwise it starts a new root trace.
    */
  def middleware[F[_]: Concurrent](
      tracer: Tracer[F]
  )(routes: HttpRoutes[F]): HttpRoutes[F] =
    HttpRoutes[F] { req =>
      val carrier: Map[String, String] =
        req.headers.headers.map(h => h.name.toString -> h.value).toMap
      val path = req.uri.path.renderString
      val spanName = s"${req.method.name} $path"

      OptionT(
        tracer.joinOrRoot(carrier) {
          tracer
            .spanBuilder(spanName)
            .withSpanKind(SpanKind.Server)
            .addAttributes(
              Attribute("http.request.method", req.method.name),
              Attribute("url.path", path)
            )
            .build
            .use { span =>
              routes.run(req).value.flatTap {
                _.traverse_(response =>
                  span.addAttribute(
                    Attribute(
                      "http.response.status_code",
                      response.status.code.toLong
                    )
                  ) *> span
                    .setStatus(StatusCode.Error)
                    .whenA(response.status.responseClass == Status.ServerError)
                )
              }
            }
        }
      )
    }
}
