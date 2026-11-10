// SPDX-License-Identifier: MIT

package orderservice

import cats.effect.{IO, Ref}
import com.comcast.ip4s._
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import inventoryservice.{InducedFailureConfig, InventoryRoutes, InventoryStore}
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.{Method, Request, Status, Uri}
import org.testcontainers.utility.DockerImageName
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.otel4s.metrics.Meter
import purerest.client.HttpClient

class OrderServicePostgresIntegrationSuite
    extends CatsEffectSuite
    with TestContainerForAll {

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(dockerImageName =
      DockerImageName.parse("postgres:16-alpine")
    )

  test("POST /orders reserves stock and persists the order in Postgres") {
    withContainers { postgres =>
      val config = PostgresConfig(
        host = postgres.host,
        port = postgres.mappedPort(5432),
        database = postgres.databaseName,
        user = postgres.username,
        password = postgres.password
      )

      val resources =
        for {
          _ <- cats.effect.Resource.eval(Migrations.run[IO](config))
          orderStore <- OrderStore.postgres[IO](config, Meter.noop[IO])
          inventoryStore <- cats.effect.Resource.eval(
            InventoryStore.inMemory[IO]
          )
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
        } yield (orderStore, inventoryServer, httpClient)

      resources.use { case (orderStore, inventoryServer, httpClient) =>
        val inventoryBaseUri =
          Uri.unsafeFromString(
            s"http://127.0.0.1:${inventoryServer.address.getPort}"
          )
        val inventoryClient = InventoryClient[IO](httpClient, inventoryBaseUri)
        val routes =
          OrderRoutes.routes[IO](orderStore, inventoryClient, NoOpLogger[IO])
        val request = Request[IO](Method.POST, uri"/orders")
          .withEntity(CreateOrderRequest("widget", 5))

        for {
          postResponse <- routes.orNotFound.run(request)
          created <- postResponse.as[OrderResponse]
          getResponse <- routes.orNotFound.run(
            Request[IO](
              Method.GET,
              Uri.unsafeFromString(s"/orders/${created.id}")
            )
          )
          fetched <- getResponse.as[OrderResponse]
        } yield {
          assertEquals(postResponse.status, Status.Created)
          assertEquals(created.item, "widget")
          assertEquals(created.quantity, 5)
          assert(created.reservationId.nonEmpty)

          assertEquals(getResponse.status, Status.Ok)
          assertEquals(fetched, created)
          assertEquals(fetched.status, "reserved")
        }
      }
    }
  }

  test(
    "POST /orders returns a clean 5xx without leaking exception internals when Postgres is unreachable"
  ) {
    withContainers { postgres =>
      val unreachableConfig = PostgresConfig(
        // Port 1 is a privileged port nothing binds to in these tests; unlike
        // `mappedPort(5432) + 1`, it can't collide with another concurrently-running
        // Testcontainers Postgres instance's dynamically assigned port.
        host = postgres.host,
        port = 1,
        database = postgres.databaseName,
        user = postgres.username,
        password = postgres.password
      )

      val resources =
        for {
          orderStore <- OrderStore
            .postgres[IO](unreachableConfig, Meter.noop[IO])
          inventoryStore <- cats.effect.Resource.eval(
            InventoryStore.inMemory[IO]
          )
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
        } yield (orderStore, inventoryServer, httpClient)

      resources.use { case (orderStore, inventoryServer, httpClient) =>
        val inventoryBaseUri =
          Uri.unsafeFromString(
            s"http://127.0.0.1:${inventoryServer.address.getPort}"
          )
        val inventoryClient = InventoryClient[IO](httpClient, inventoryBaseUri)
        val routes =
          OrderRoutes.routes[IO](orderStore, inventoryClient, NoOpLogger[IO])
        val request = Request[IO](Method.POST, uri"/orders")
          .withEntity(CreateOrderRequest("widget", 5))

        for {
          response <- routes.orNotFound.run(request)
          body <- response.bodyText.compile.string
        } yield {
          assert(
            response.status.code >= 500,
            s"expected a 5xx status, got ${response.status}"
          )
          assert(
            !body.contains("Exception"),
            s"response body leaked exception details: $body"
          )
          assert(
            !body.toLowerCase.contains("skunk"),
            s"response body leaked Skunk internals: $body"
          )
        }
      }
    }
  }
}
