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
import org.typelevel.otel4s.oteljava.testkit.trace.{
  SpanExpectation,
  TraceExpectation,
  TraceExpectations,
  TraceForestExpectation
}
import purerest.client.HttpClient
import purerest.tracing.{ClientTracing, ServerTracing, Tracing}

class OrderServiceTraceContinuitySuite extends CatsEffectSuite {

  test(
    "a single trace spans the inbound order-service request and the outbound inventory-service call"
  ) {
    Tracing.test[IO]("trace-continuity-test").use { testTracer =>
      val tracer = testTracer.tracer

      val resources =
        for {
          inventoryStore <- cats.effect.Resource.eval(
            InventoryStore.inMemory[IO]
          )
          inventoryConfigRef <- cats.effect.Resource.eval(
            Ref.of[IO, InducedFailureConfig](InducedFailureConfig.disabled)
          )
          inventoryRoutes = ServerTracing.middleware(tracer)(
            InventoryRoutes
              .routes[IO](inventoryStore, NoOpLogger[IO], inventoryConfigRef)
          )
          inventoryServer <- EmberServerBuilder
            .default[IO]
            .withHost(host"127.0.0.1")
            .withPort(port"0")
            .withHttpApp(inventoryRoutes.orNotFound)
            .build
          httpClient <- HttpClient.resource[IO]
        } yield (inventoryServer, httpClient)

      resources.use { case (inventoryServer, httpClient) =>
        val inventoryBaseUri =
          Uri.unsafeFromString(
            s"http://127.0.0.1:${inventoryServer.address.getPort}"
          )
        val tracedClient = ClientTracing.middleware(tracer)(httpClient)
        val inventoryClient =
          InventoryClient[IO](tracedClient, inventoryBaseUri)

        for {
          orderStore <- OrderStore.inMemory[IO]
          orderRoutes = ServerTracing.middleware(tracer)(
            OrderRoutes.routes[IO](orderStore, inventoryClient, NoOpLogger[IO])
          )
          request = Request[IO](Method.POST, uri"/orders")
            .withEntity(CreateOrderRequest("widget", 7))
          response <- orderRoutes.orNotFound.run(request)
          order <- response.as[OrderResponse]
          spans <- testTracer.finishedSpans
        } yield {
          assertEquals(response.status, Status.Created)
          assertEquals(order.item, "widget")
          assertEquals(order.quantity, 7)

          val expected = TraceForestExpectation.ordered(
            TraceExpectation.ordered(
              SpanExpectation.any.name("POST /orders"),
              TraceExpectation.ordered(
                SpanExpectation.any.name("POST 127.0.0.1"),
                TraceExpectation
                  .leaf(SpanExpectation.any.name("POST /inventory/reserve"))
              )
            )
          )
          TraceExpectations.check(spans, expected) match {
            case Right(())        => ()
            case Left(mismatches) => fail(TraceExpectations.format(mismatches))
          }
        }
      }
    }
  }
}
