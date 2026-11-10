// SPDX-License-Identifier: MIT

package orderservice

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger.{
  ERROR,
  INFO,
  WARN
}
import purerest.tracing.{ServerTracing, Tracing}

class OrderRoutesSuite extends CatsEffectSuite {

  private val stubInventory: InventoryClient[IO] = new InventoryClient[IO] {
    def reserve(item: String, quantity: Int): IO[ReservationView] =
      IO.pure(ReservationView("stub-reservation-id", item, quantity))
  }

  private def failingInventory(error: Throwable): InventoryClient[IO] =
    new InventoryClient[IO] {
      def reserve(item: String, quantity: Int): IO[ReservationView] =
        IO.raiseError(error)
    }

  private def failingStore(error: Throwable): OrderStore[IO] =
    new OrderStore[IO] {
      def create(
          item: String,
          quantity: Int,
          reservationId: String,
          reservedQuantity: Int
      ): IO[Order] = IO.raiseError(error)
      def get(id: String): IO[Option[Order]] = IO.pure(None)
    }

  test("POST /orders returns 201 with the order and inventory reservation id") {
    for {
      store <- OrderStore.inMemory[IO]
      routes = OrderRoutes.routes[IO](store, stubInventory, NoOpLogger[IO])
      request = Request[IO](Method.POST, uri"/orders")
        .withEntity(CreateOrderRequest("widget", 4))
      response <- routes.orNotFound.run(request)
      order <- response.as[OrderResponse]
    } yield {
      assertEquals(response.status, Status.Created)
      assertEquals(order.item, "widget")
      assertEquals(order.quantity, 4)
      assertEquals(order.reservationId, "stub-reservation-id")
    }
  }

  test("GET /orders/{id} returns 200 with the persisted order") {
    for {
      store <- OrderStore.inMemory[IO]
      routes = OrderRoutes.routes[IO](store, stubInventory, NoOpLogger[IO])
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/orders").withEntity(
          CreateOrderRequest("widget", 4)
        )
      )
      created <- postResponse.as[OrderResponse]
      getResponse <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/orders" / created.id)
      )
      fetched <- getResponse.as[OrderResponse]
    } yield {
      assertEquals(getResponse.status, Status.Ok)
      assertEquals(fetched, created)
    }
  }

  test(
    "GET /orders/{id} returns 404 with a JSON error body for an unknown id"
  ) {
    for {
      store <- OrderStore.inMemory[IO]
      routes = OrderRoutes.routes[IO](store, stubInventory, NoOpLogger[IO])
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/orders" / "unknown-id")
      )
      body <- response.as[io.circe.Json]
    } yield {
      assertEquals(response.status, Status.NotFound)
      assert(
        body.asObject.exists(_.contains("error")),
        s"expected a JSON error body, got: $body"
      )
    }
  }

  test(
    "POST /orders logs a received-request line and a completed line with structured context"
  ) {
    for {
      store <- OrderStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = OrderRoutes.routes[IO](store, stubInventory, testLogger)
      request = Request[IO](Method.POST, uri"/orders")
        .withEntity(CreateOrderRequest("widget", 4))
      response <- routes.orNotFound.run(request)
      order <- response.as[OrderResponse]
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
            m.ctx.get("order_id").contains(order.id) &&
            m.ctx.get("reservation_id").contains(order.reservationId)
        ),
        s"expected a completed INFO line with order_id/reservation_id context, got: $infos"
      )
    }
  }

  test(
    "POST /orders logs an ERROR with context when the inventory reservation fails"
  ) {
    val boom = new RuntimeException("boom")
    for {
      store <- OrderStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = OrderRoutes.routes[IO](store, failingInventory(boom), testLogger)
      request = Request[IO](Method.POST, uri"/orders")
        .withEntity(CreateOrderRequest("widget", 4))
      response <- routes.orNotFound.run(request)
      logged <- testLogger.logged
    } yield {
      assertEquals(response.status, Status.ServiceUnavailable)
      val errors = logged.collect { case m: ERROR => m }
      assert(
        errors.exists(m =>
          m.ctx.get("item").contains("widget") && m.throwOpt.contains(boom)
        ),
        s"expected an ERROR line with item context and the raised throwable, got: $errors"
      )
    }
  }

  test(
    "POST /orders logs an ERROR with context when persisting the order fails"
  ) {
    val boom = new RuntimeException("boom")
    for {
      testLogger <- IO.pure(StructuredTestingLogger.impl[IO]())
      routes = OrderRoutes
        .routes[IO](failingStore(boom), stubInventory, testLogger)
      request = Request[IO](Method.POST, uri"/orders")
        .withEntity(CreateOrderRequest("widget", 4))
      response <- routes.orNotFound.run(request)
      logged <- testLogger.logged
    } yield {
      assertEquals(response.status, Status.InternalServerError)
      val errors = logged.collect { case m: ERROR => m }
      assert(
        errors.exists(m =>
          m.ctx.get("item").contains("widget") && m.throwOpt.contains(boom)
        ),
        s"expected an ERROR line with item context and the raised throwable, got: $errors"
      )
    }
  }

  test(
    "GET /orders/{id} logs a received-request line and a completed line for a found order"
  ) {
    for {
      store <- OrderStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = OrderRoutes.routes[IO](store, stubInventory, testLogger)
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/orders").withEntity(
          CreateOrderRequest("widget", 4)
        )
      )
      created <- postResponse.as[OrderResponse]
      _ <- testLogger.logged // drain POST's own log lines before the GET
      getResponse <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/orders" / created.id)
      )
      logged <- testLogger.logged
    } yield {
      assertEquals(getResponse.status, Status.Ok)
      val infos = logged.collect { case m: INFO => m }
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("received") &&
            m.ctx.get("method").contains("GET") &&
            m.ctx.get("order_id").contains(created.id)
        ),
        s"expected a received-request INFO line with method/order_id context, got: $infos"
      )
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("completed") &&
            m.ctx.get("order_id").contains(created.id)
        ),
        s"expected a completed INFO line with order_id context, got: $infos"
      )
    }
  }

  test(
    "GET /orders/{id} logs a WARN for an unknown id"
  ) {
    for {
      store <- OrderStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = OrderRoutes.routes[IO](store, stubInventory, testLogger)
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/orders" / "unknown-id")
      )
      logged <- testLogger.logged
    } yield {
      assertEquals(response.status, Status.NotFound)
      val warns = logged.collect { case m: WARN => m }
      assert(
        warns.exists(m =>
          m.message.toLowerCase.contains("not found") &&
            m.ctx.get("order_id").contains("unknown-id")
        ),
        s"expected a 'not found' WARN line with order_id context, got: $warns"
      )
    }
  }

  test(
    "wrapped routes (with tracing middleware) record a span for a handled request"
  ) {
    Tracing.test[IO]("order-service-test").use { testTracer =>
      for {
        store <- OrderStore.inMemory[IO]
        routes = ServerTracing.middleware(testTracer.tracer)(
          OrderRoutes.routes[IO](store, stubInventory, NoOpLogger[IO])
        )
        request = Request[IO](Method.POST, uri"/orders")
          .withEntity(CreateOrderRequest("widget", 4))
        response <- routes.orNotFound.run(request)
        spans <- testTracer.finishedSpans
      } yield {
        assertEquals(response.status, Status.Created)
        assertEquals(spans.map(_.getName), List("POST /orders"))
      }
    }
  }
}
