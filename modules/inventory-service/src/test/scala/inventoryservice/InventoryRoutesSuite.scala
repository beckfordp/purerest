// SPDX-License-Identifier: MIT

package inventoryservice

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.http4s.{HttpRoutes, Method, Request, Status}
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger.{INFO, WARN}
import purerest.tracing.{ServerTracing, Tracing}

import scala.concurrent.duration._

class InventoryRoutesSuite extends CatsEffectSuite {

  test("POST /inventory/reserve returns 201 with a reservation") {
    for {
      store <- InventoryStore.inMemory[IO]
      configRef <- Ref.of[IO, InducedFailureConfig](
        InducedFailureConfig.disabled
      )
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO], configRef)
      request = Request[IO](Method.POST, uri"/inventory/reserve")
        .withEntity(ReserveRequest("widget", 3))
      response <- routes.orNotFound.run(request)
      reservation <- response.as[Reservation]
    } yield {
      assertEquals(response.status, Status.Created)
      assertEquals(reservation.item, "widget")
      assertEquals(reservation.quantity, 3)
    }
  }

  test(
    "with induced failure rate 1.0, POST /inventory/reserve always returns 500"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      configRef <- Ref.of[IO, InducedFailureConfig](
        InducedFailureConfig(failureRate = 1.0, delay = Duration.Zero)
      )
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO], configRef)
      request = Request[IO](Method.POST, uri"/inventory/reserve")
        .withEntity(ReserveRequest("widget", 3))
      response <- routes.orNotFound.run(request)
    } yield assertEquals(response.status, Status.InternalServerError)
  }

  test(
    "with a tiny positive induced failure rate, POST /inventory/reserve still usually succeeds"
  ) {
    // Exercises the branch where failureRate > 0 but the random roll doesn't trigger
    // a failure — distinct from the failureRate <= 0.0 short-circuit tested below.
    // Flake probability is astronomically low (~1e-7).
    for {
      store <- InventoryStore.inMemory[IO]
      configRef <- Ref.of[IO, InducedFailureConfig](
        InducedFailureConfig(failureRate = 0.0000001, delay = Duration.Zero)
      )
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO], configRef)
      request = Request[IO](Method.POST, uri"/inventory/reserve")
        .withEntity(ReserveRequest("widget", 3))
      response <- routes.orNotFound.run(request)
    } yield assertEquals(response.status, Status.Created)
  }

  test(
    "with induced failure rate 0.0, POST /inventory/reserve still succeeds"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      configRef <- Ref.of[IO, InducedFailureConfig](
        InducedFailureConfig(failureRate = 0.0, delay = Duration.Zero)
      )
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO], configRef)
      request = Request[IO](Method.POST, uri"/inventory/reserve")
        .withEntity(ReserveRequest("widget", 3))
      response <- routes.orNotFound.run(request)
    } yield assertEquals(response.status, Status.Created)
  }

  test("induced delay measurably delays the response") {
    for {
      store <- InventoryStore.inMemory[IO]
      configRef <- Ref.of[IO, InducedFailureConfig](
        InducedFailureConfig(failureRate = 0.0, delay = 200.millis)
      )
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO], configRef)
      request = Request[IO](Method.POST, uri"/inventory/reserve")
        .withEntity(ReserveRequest("widget", 3))
      start <- IO.monotonic
      response <- routes.orNotFound.run(request)
      end <- IO.monotonic
    } yield {
      assertEquals(response.status, Status.Created)
      assert(
        (end - start) >= 200.millis,
        s"expected at least a 200ms delay, took ${end - start}"
      )
    }
  }

  test(
    "POST /inventory/reserve logs a received-request line and a completed line with structured context"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      configRef <- Ref.of[IO, InducedFailureConfig](
        InducedFailureConfig.disabled
      )
      routes = InventoryRoutes.routes[IO](store, testLogger, configRef)
      request = Request[IO](Method.POST, uri"/inventory/reserve")
        .withEntity(ReserveRequest("widget", 3))
      response <- routes.orNotFound.run(request)
      reservation <- response.as[Reservation]
      logged <- testLogger.logged
    } yield {
      val infos = logged.collect { case m: INFO => m }
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("received") &&
            m.ctx.get("method").contains("POST") &&
            m.ctx.get("item").contains("widget")
        ),
        s"expected a received-request INFO line with method/item context, got: $infos"
      )
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("completed") &&
            m.ctx.get("reservation_id").contains(reservation.id)
        ),
        s"expected a completed INFO line with reservation_id context, got: $infos"
      )
    }
  }

  test(
    "with induced failure rate 1.0, the induced-failure trigger logs a WARN with item/quantity context"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      configRef <- Ref.of[IO, InducedFailureConfig](
        InducedFailureConfig(failureRate = 1.0, delay = Duration.Zero)
      )
      routes = InventoryRoutes.routes[IO](store, testLogger, configRef)
      request = Request[IO](Method.POST, uri"/inventory/reserve")
        .withEntity(ReserveRequest("widget", 3))
      response <- routes.orNotFound.run(request)
      logged <- testLogger.logged
    } yield {
      assertEquals(response.status, Status.InternalServerError)
      val warns = logged.collect { case m: WARN => m }
      assert(
        warns.exists(m =>
          m.message.toLowerCase.contains("induced failure") &&
            m.ctx.get("item").contains("widget") &&
            m.ctx.get("quantity").contains("3")
        ),
        s"expected a WARN line for the induced failure with item/quantity context, got: $warns"
      )
    }
  }

  test("unmatched routes return 404") {
    for {
      store <- InventoryStore.inMemory[IO]
      configRef <- Ref.of[IO, InducedFailureConfig](
        InducedFailureConfig.disabled
      )
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO], configRef)
      request = Request[IO](Method.GET, uri"/nope")
      response <- routes.orNotFound.run(request)
    } yield assertEquals(response.status, Status.NotFound)
  }

  test(
    "wrapped routes (with tracing middleware) record a span for a handled request"
  ) {
    Tracing.test[IO]("inventory-service-test").use { testTracer =>
      for {
        store <- InventoryStore.inMemory[IO]
        configRef <- Ref.of[IO, InducedFailureConfig](
          InducedFailureConfig.disabled
        )
        routes = ServerTracing.middleware(testTracer.tracer)(
          InventoryRoutes.routes[IO](store, NoOpLogger[IO], configRef)
        )
        request = Request[IO](Method.POST, uri"/inventory/reserve")
          .withEntity(ReserveRequest("widget", 3))
        response <- routes.orNotFound.run(request)
        spans <- testTracer.finishedSpans
      } yield {
        assertEquals(response.status, Status.Created)
        assertEquals(spans.map(_.getName), List("POST /inventory/reserve"))
      }
    }
  }

  test("GET /admin/induced-failure returns the current config") {
    for {
      store <- InventoryStore.inMemory[IO]
      configRef <- Ref.of[IO, InducedFailureConfig](
        InducedFailureConfig(failureRate = 0.25, delay = 50.millis)
      )
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO], configRef)
      request = Request[IO](Method.GET, uri"/admin/induced-failure")
      response <- routes.orNotFound.run(request)
      view <- response.as[InducedFailureView]
    } yield {
      assertEquals(response.status, Status.Ok)
      assertEquals(view, InducedFailureView(failureRate = 0.25, delayMs = 50L))
    }
  }

  test(
    "GET /admin/induced-failure reflects the disabled default when unset"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      configRef <- Ref.of[IO, InducedFailureConfig](
        InducedFailureConfig.disabled
      )
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO], configRef)
      request = Request[IO](Method.GET, uri"/admin/induced-failure")
      response <- routes.orNotFound.run(request)
      view <- response.as[InducedFailureView]
    } yield {
      assertEquals(response.status, Status.Ok)
      assertEquals(view, InducedFailureView(failureRate = 0.0, delayMs = 0L))
    }
  }

  test(
    "PATCH /admin/induced-failure updates the live config, reflected by a subsequent GET"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      configRef <- Ref.of[IO, InducedFailureConfig](
        InducedFailureConfig.disabled
      )
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO], configRef)
      patchRequest = Request[IO](Method.PATCH, uri"/admin/induced-failure")
        .withEntity(InducedFailureView(failureRate = 0.5, delayMs = 100L))
      patchResponse <- routes.orNotFound.run(patchRequest)
      patchedView <- patchResponse.as[InducedFailureView]
      getResponse <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/admin/induced-failure")
      )
      getView <- getResponse.as[InducedFailureView]
    } yield {
      assertEquals(patchResponse.status, Status.Ok)
      assertEquals(
        patchedView,
        InducedFailureView(failureRate = 0.5, delayMs = 100L)
      )
      assertEquals(getResponse.status, Status.Ok)
      assertEquals(
        getView,
        InducedFailureView(failureRate = 0.5, delayMs = 100L)
      )
    }
  }

  test(
    "PATCH /admin/induced-failure rejects failureRate outside [0.0, 1.0] with 400, leaving the config unchanged"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      configRef <- Ref.of[IO, InducedFailureConfig](
        InducedFailureConfig.disabled
      )
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO], configRef)
      patchRequest = Request[IO](Method.PATCH, uri"/admin/induced-failure")
        .withEntity(InducedFailureView(failureRate = 1.5, delayMs = 0L))
      patchResponse <- routes.orNotFound.run(patchRequest)
      errorBody <- patchResponse.as[ErrorResponse]
      configAfter <- configRef.get
    } yield {
      assertEquals(patchResponse.status, Status.BadRequest)
      assert(errorBody.error.nonEmpty)
      assertEquals(configAfter, InducedFailureConfig.disabled)
    }
  }

  test(
    "PATCH /admin/induced-failure rejects a negative delayMs with 400, leaving the config unchanged"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      configRef <- Ref.of[IO, InducedFailureConfig](
        InducedFailureConfig.disabled
      )
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO], configRef)
      patchRequest = Request[IO](Method.PATCH, uri"/admin/induced-failure")
        .withEntity(InducedFailureView(failureRate = 0.2, delayMs = -1L))
      patchResponse <- routes.orNotFound.run(patchRequest)
      configAfter <- configRef.get
    } yield {
      assertEquals(patchResponse.status, Status.BadRequest)
      assertEquals(configAfter, InducedFailureConfig.disabled)
    }
  }

  test(
    "the induced-failure rate takes effect live, without a restart: baseline -> PATCH to 1.0 -> reserve fails -> PATCH to 0.0 -> reserve succeeds"
  ) {
    def reserve(routes: HttpRoutes[IO]) =
      routes.orNotFound.run(
        Request[IO](Method.POST, uri"/inventory/reserve")
          .withEntity(ReserveRequest("widget", 3))
      )

    def patch(routes: HttpRoutes[IO], failureRate: Double) =
      routes.orNotFound.run(
        Request[IO](Method.PATCH, uri"/admin/induced-failure")
          .withEntity(
            InducedFailureView(failureRate = failureRate, delayMs = 0L)
          )
      )

    for {
      store <- InventoryStore.inMemory[IO]
      configRef <- Ref.of[IO, InducedFailureConfig](
        InducedFailureConfig.disabled
      )
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO], configRef)
      baselineGet <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/admin/induced-failure")
      )
      baselineView <- baselineGet.as[InducedFailureView]
      baselineReserve <- reserve(routes)
      patchToFailing <- patch(routes, 1.0)
      reserveWhileFailing <- reserve(routes)
      patchToHealthy <- patch(routes, 0.0)
      reserveWhileHealthy <- reserve(routes)
    } yield {
      assertEquals(
        baselineView,
        InducedFailureView(failureRate = 0.0, delayMs = 0L)
      )
      assertEquals(baselineReserve.status, Status.Created)
      assertEquals(patchToFailing.status, Status.Ok)
      assertEquals(reserveWhileFailing.status, Status.InternalServerError)
      assertEquals(patchToHealthy.status, Status.Ok)
      assertEquals(reserveWhileHealthy.status, Status.Created)
    }
  }

  test(
    "InducedFailureConfig.validated rejects a NaN failureRate (not just out-of-range values)"
  ) {
    assertEquals(
      InducedFailureConfig.validated(Double.NaN, delayMs = 0L),
      Left(InvalidInducedFailureConfig)
    )
  }
}
