package purerest.metrics

import cats.data.OptionT
import cats.effect.{Async, Clock, Ref}
import cats.syntax.all._
import org.http4s.HttpRoutes
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{Histogram, Meter}

import scala.concurrent.duration.SECONDS
import scala.util.control.Exception.allCatch

/** Server-side RED metrics middleware for http4s. */
object ServerMetrics {

  /** Approximates the OTel-recommended low-cardinality route template for the
    * `http.route` attribute. `ServerMetrics.middleware` wraps
    * already-interpreted `HttpRoutes[F]` (post-tapir), so it has no access to
    * the real tapir route templates (e.g. `/orders/{id}`) — unlike
    * `ServerTracing`'s span name, this attribute feeds a Prometheus label,
    * where using the raw request path would grow one time series per distinct
    * path value forever. As a targeted mitigation (not a general fix — only
    * catches UUID-shaped segments, the shape every path parameter in this
    * codebase currently uses), any path segment that parses as a UUID is
    * collapsed to `{id}`.
    */
  private[metrics] def routeTemplate(path: String): String =
    path
      .split("/", -1)
      .map { segment =>
        if (allCatch.opt(java.util.UUID.fromString(segment)).isDefined) "{id}"
        else segment
      }
      .mkString("/")

  /** Wraps `HttpRoutes[F]` with a `http.server.request.duration` histogram
    * measurement (seconds, [[LatencyBuckets.seconds]] boundaries) per handled
    * request, tagged with the request method, route, and — when the route
    * matched — the response status code, or an `error.type` attribute if the
    * route raised instead of returning a response (so the "Errors" dimension of
    * RED metrics doesn't silently miss handler failures — OTel
    * semantic-convention attribute names throughout). The histogram instrument
    * is created once, on first use, and reused for every subsequent request
    * rather than recreated per request.
    */
  def middleware[F[_]: Async](
      meter: Meter[F]
  )(routes: HttpRoutes[F]): HttpRoutes[F] = {
    val histogramRef: Ref[F, Option[Histogram[F, Double]]] =
      Ref.unsafe(None)

    val getHistogram: F[Histogram[F, Double]] =
      histogramRef.get.flatMap {
        case Some(histogram) => histogram.pure[F]
        case None            =>
          meter
            .histogram[Double]("http.server.request.duration")
            .withUnit("s")
            .withExplicitBucketBoundaries(LatencyBuckets.seconds)
            .create
            .flatTap(histogram => histogramRef.set(Some(histogram)))
      }

    HttpRoutes[F] { req =>
      OptionT(
        for {
          histogram <- getHistogram
          start <- Clock[F].monotonic
          result <- routes.run(req).value.attempt
          end <- Clock[F].monotonic
          outcomeAttributes = result match {
            case Right(maybeResponse) =>
              maybeResponse
                .map(r =>
                  Attribute("http.response.status_code", r.status.code.toLong)
                )
                .toList
            case Left(error) =>
              List(Attribute("error.type", error.getClass.getName))
          }
          attributes = List(
            Attribute("http.request.method", req.method.name),
            Attribute("http.route", routeTemplate(req.uri.path.renderString))
          ) ++ outcomeAttributes
          _ <- histogram.record((end - start).toUnit(SECONDS), attributes)
          response <- result.liftTo[F]
        } yield response
      )
    }
  }
}
