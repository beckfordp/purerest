package purerest.metrics

import cats.effect.{Async, Clock, Ref, Resource}
import cats.syntax.all._
import org.http4s.client.Client
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{Histogram, Meter}

import scala.concurrent.duration.SECONDS

/** Client-side RED metrics middleware for http4s. */
object ClientMetrics {

  /** Wraps a `Client[F]` with a `http.client.request.duration` histogram
    * measurement (seconds, [[LatencyBuckets.seconds]] boundaries) per outgoing
    * call, tagged with the request method, server address, and response status
    * code, or an `error.type` attribute if the call raised instead of returning
    * a response (so the "Errors" dimension of RED metrics doesn't silently miss
    * connection failures/timeouts — OTel semantic-convention attribute names
    * throughout). The histogram instrument is created once, on first use, and
    * reused for every subsequent call rather than recreated per call.
    */
  def middleware[F[_]: Async](meter: Meter[F])(client: Client[F]): Client[F] = {
    val histogramRef: Ref[F, Option[Histogram[F, Double]]] =
      Ref.unsafe(None)

    val getHistogram: F[Histogram[F, Double]] =
      histogramRef.get.flatMap {
        case Some(histogram) => histogram.pure[F]
        case None            =>
          meter
            .histogram[Double]("http.client.request.duration")
            .withUnit("s")
            .withExplicitBucketBoundaries(LatencyBuckets.seconds)
            .create
            .flatTap(histogram => histogramRef.set(Some(histogram)))
      }

    Client[F] { req =>
      for {
        histogram <- Resource.eval(getHistogram)
        start <- Resource.eval(Clock[F].monotonic)
        result <- client.run(req).attempt
        end <- Resource.eval(Clock[F].monotonic)
        outcomeAttributes = result match {
          case Right(response) =>
            List(
              Attribute(
                "http.response.status_code",
                response.status.code.toLong
              )
            )
          case Left(error) =>
            List(Attribute("error.type", error.getClass.getName))
        }
        _ <- Resource.eval(
          histogram.record(
            (end - start).toUnit(SECONDS),
            List(
              Attribute("http.request.method", req.method.name),
              Attribute(
                "server.address",
                req.uri.authority.map(_.host.value).getOrElse("")
              )
            ) ++ outcomeAttributes
          )
        )
        response <- Resource.eval(result.liftTo[F])
      } yield response
    }
  }
}
