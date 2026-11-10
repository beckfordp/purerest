// SPDX-License-Identifier: MIT

package smoketest

import cats.data.OptionT
import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.implicits._
import org.http4s.{HttpRoutes, Request, Response, Status}
import purerest.metrics.{Metrics, ServerMetrics}

/** Proves purerest works when resolved purely as a published jar (via this
  * standalone build's own `libraryDependencies`, not the main repo's internal
  * `.dependsOn(purerest)` project reference) — not just that it compiles, but
  * that a real request through `ServerMetrics.middleware` genuinely records a
  * measurement, using classes/resources that only exist in the jar itself.
  */
class PurerestConsumptionSuite extends CatsEffectSuite {

  private val routes: HttpRoutes[IO] =
    HttpRoutes[IO] { _ => OptionT.liftF(IO.pure(Response[IO](Status.Ok))) }

  test(
    "ServerMetrics.middleware, resolved from the published purerest jar, " +
      "records a real http.server.request.duration measurement"
  ) {
    Metrics.test[IO]("purerest-consumer-smoke-test").use { testMeter =>
      val wrapped = ServerMetrics.middleware(testMeter.meter)(routes)
      for {
        response <- wrapped.orNotFound.run(Request[IO](uri = uri"/ping"))
        metrics <- testMeter.collectMetrics
      } yield {
        assertEquals(response.status, Status.Ok)
        assert(
          metrics.exists(_.getName == "http.server.request.duration"),
          s"expected an http.server.request.duration series, got: ${metrics.map(_.getName)}"
        )
      }
    }
  }
}
