// SPDX-License-Identifier: MIT

package loadtest

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._

/** Repeatedly places orders against order-service's `POST /orders` — the call
  * path that drives order-service -> inventory-service via purerest's resilient
  * client (tracing, metrics, retry, circuit breaker). Run via
  * `sbt loadTest/Gatling/test` (not the default `test` task) against a running
  * order-service, or via `scripts/verify-observability-stack.sh`, which also
  * orchestrates bringing the stack up and verifying the results.
  *
  * `baseUrl` is overridable (`-DbaseUrl=...`) so this can point at a different
  * host/port without editing the simulation.
  */
class OrderPlacementSimulation extends Simulation {

  private val baseUrl = System.getProperty("baseUrl", "http://localhost:8080")
  private val inventoryBaseUrl =
    System.getProperty("inventoryBaseUrl", "http://localhost:8081")

  private val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")

  private val placeOrder =
    exec(
      http("POST /orders")
        .post("/orders")
        .body(StringBody("""{"item": "widget", "quantity": 1}"""))
    )

  private val scn = scenario("Place orders").exec(placeOrder)

  private def patchFailureRate(rate: Double) =
    exec(
      http(s"PATCH /admin/induced-failure ($rate)")
        .patch(inventoryBaseUrl + "/admin/induced-failure")
        .body(StringBody(s"""{"failureRate": $rate, "delayMs": 0}"""))
    )

  // Ramps inventory-service's induced-failure rate live, concurrently with the
  // order-placement load below, via the runtime-adjustable admin endpoint (no
  // container restart needed): healthy baseline -> a rate known to reliably
  // trip order-service's circuit breaker (failureThreshold=5, see
  // orderservice.Main) -> back to healthy. Each non-baseline phase is sized to
  // comfortably clear order-service's 30s resetTimeout, so a full trip AND
  // recovery are both observable within one run. Starting values — calibrate
  // empirically against a live stack the same way the prior track tuned its
  // degraded rate from 0.3 to 0.5.
  private val rampFailureRate = scenario("Ramp induced-failure rate")
    .exec(patchFailureRate(0.0))
    .pause(20.seconds)
    .exec(patchFailureRate(0.6))
    .pause(70.seconds)
    .exec(patchFailureRate(0.0))
    .pause(40.seconds)

  // Ramp to ~15 concurrent users over 30s, then hold at ~5 users/sec for a
  // further 100s (~130s total, matching rampFailureRate's full schedule above)
  // — enough sustained traffic for RED-metric histograms/counters (and, under
  // induced failure, resilience counters) to look like real distributions,
  // without needing more than a laptop running docker-compose.
  setUp(
    scn.inject(
      rampUsers(15).during(30.seconds),
      constantUsersPerSec(5).during(100.seconds)
    ),
    rampFailureRate.inject(atOnceUsers(1))
  ).protocols(httpProtocol)
}
