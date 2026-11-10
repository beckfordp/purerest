// SPDX-License-Identifier: MIT

package orderservice

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}
import org.typelevel.log4cats.noop.NoOpLogger
import purerest.docs.Docs

class OrderDocsSuite extends CatsEffectSuite {

  private val stubInventory: InventoryClient[IO] = new InventoryClient[IO] {
    def reserve(item: String, quantity: Int): IO[ReservationView] =
      IO.pure(ReservationView("stub-reservation-id", item, quantity))
  }

  test(
    "the tapir-described endpoint is served and documented via purerest.docs"
  ) {
    for {
      store <- OrderStore.inMemory[IO]
      endpoint = OrderRoutes
        .serverEndpoint[IO](store, stubInventory, NoOpLogger[IO])
      routes = Docs.routes[IO]("Order Service", "1.0", List(endpoint))
      request = Request[IO](Method.POST, uri"/orders")
        .withEntity(CreateOrderRequest("widget", 4))
      response <- routes.orNotFound.run(request)
      order <- response.as[OrderResponse]
      docsResponse <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/docs/docs.yaml")
      )
      docsBody <- docsResponse.bodyText.compile.string
    } yield {
      assertEquals(response.status, Status.Created)
      assertEquals(order.item, "widget")
      assertEquals(order.quantity, 4)
      assertEquals(order.reservationId, "stub-reservation-id")
      assertEquals(docsResponse.status, Status.Ok)
      assert(clue(docsBody).contains("/orders"))
    }
  }
}
