// SPDX-License-Identifier: MIT

package inventoryservice

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}
import org.typelevel.log4cats.noop.NoOpLogger
import purerest.docs.Docs

class InventoryDocsSuite extends CatsEffectSuite {

  test(
    "the tapir-described endpoint is served and documented via purerest.docs"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      configRef <- Ref.of[IO, InducedFailureConfig](
        InducedFailureConfig.disabled
      )
      endpoint = InventoryRoutes
        .reserveServerEndpoint[IO](store, NoOpLogger[IO], configRef)
      routes = Docs.routes[IO]("Inventory Service", "1.0", List(endpoint))
      request = Request[IO](Method.POST, uri"/inventory/reserve")
        .withEntity(ReserveRequest("widget", 3))
      response <- routes.orNotFound.run(request)
      reservation <- response.as[Reservation]
      docsResponse <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/docs/docs.yaml")
      )
      docsBody <- docsResponse.bodyText.compile.string
    } yield {
      assertEquals(response.status, Status.Created)
      assertEquals(reservation.item, "widget")
      assertEquals(reservation.quantity, 3)
      assertEquals(docsResponse.status, Status.Ok)
      assert(clue(docsBody).contains("/inventory/reserve"))
    }
  }

  test("the admin induced-failure endpoints are documented via purerest.docs") {
    for {
      store <- InventoryStore.inMemory[IO]
      configRef <- Ref.of[IO, InducedFailureConfig](
        InducedFailureConfig.disabled
      )
      routes = Docs.routes[IO](
        "Inventory Service",
        "1.0",
        List(
          InventoryRoutes
            .reserveServerEndpoint[IO](store, NoOpLogger[IO], configRef),
          InventoryRoutes.getInducedFailureServerEndpoint[IO](configRef),
          InventoryRoutes.patchInducedFailureServerEndpoint[IO](configRef)
        )
      )
      docsResponse <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/docs/docs.yaml")
      )
      docsBody <- docsResponse.bodyText.compile.string
    } yield {
      assertEquals(docsResponse.status, Status.Ok)
      assert(clue(docsBody).contains("/admin/induced-failure"))
    }
  }
}
