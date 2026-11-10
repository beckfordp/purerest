// SPDX-License-Identifier: MIT

package orderservice

import cats.effect.{IO, Ref}
import com.comcast.ip4s._
import inventoryservice.{InducedFailureConfig, InventoryRoutes, InventoryStore}
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.{Method, Request, Status, Uri}
import org.typelevel.log4cats.noop.NoOpLogger
import purerest.client.HttpClient

class OrderServiceIntegrationSuite extends CatsEffectSuite {

  test(
    "POST /orders reserves stock on a real inventory-service and persists the order"
  ) {
    val resources =
      for {
        inventoryStore <- cats.effect.Resource.eval(InventoryStore.inMemory[IO])
        inventoryConfigRef <- cats.effect.Resource.eval(
          Ref.of[IO, InducedFailureConfig](InducedFailureConfig.disabled)
        )
        inventoryServer <- EmberServerBuilder
          .default[IO]
          .withHost(host"127.0.0.1")
          .withPort(port"0")
          .withHttpApp(
            InventoryRoutes
              .routes[IO](inventoryStore, NoOpLogger[IO], inventoryConfigRef)
              .orNotFound
          )
          .build
        httpClient <- HttpClient.resource[IO]
      } yield (inventoryServer, httpClient)

    resources.use { case (inventoryServer, httpClient) =>
      val inventoryBaseUri =
        Uri.unsafeFromString(
          s"http://127.0.0.1:${inventoryServer.address.getPort}"
        )
      val inventoryClient = InventoryClient[IO](httpClient, inventoryBaseUri)

      for {
        orderStore <- OrderStore.inMemory[IO]
        routes = OrderRoutes
          .routes[IO](orderStore, inventoryClient, NoOpLogger[IO])
        request = Request[IO](Method.POST, uri"/orders")
          .withEntity(CreateOrderRequest("widget", 5))
        response <- routes.orNotFound.run(request)
        order <- response.as[OrderResponse]
      } yield {
        assertEquals(response.status, Status.Created)
        assertEquals(order.item, "widget")
        assertEquals(order.quantity, 5)
        assert(order.reservationId.nonEmpty)
      }
    }
  }
}
