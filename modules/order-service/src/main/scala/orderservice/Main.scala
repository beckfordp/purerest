package orderservice

import cats.effect.{IO, IOApp}
import com.comcast.ip4s._
import org.http4s.Uri
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import purerest.client.HttpClient
import purerest.docs.Docs
import purerest.logging.Logging
import purerest.metrics.{ClientMetrics, Metrics, ServerMetrics}
import purerest.resilience.Resilience
import purerest.tracing.{ClientTracing, ServerTracing, Tracing}

object Main extends IOApp.Simple {

  val run: IO[Unit] =
    for {
      config <- OrderServiceConfig.load[IO]
      port <- IO.fromOption(Port.fromInt(config.port))(
        new IllegalArgumentException(
          s"Invalid order-service port: ${config.port}"
        )
      )
      inventoryServiceBaseUri <- IO.fromEither(
        Uri.fromString(config.inventoryServiceBaseUrl)
      )
      _ <- Migrations.run[IO](config.postgres)
      _ <- Tracing.console[IO](config.serviceName).use { tracer =>
        Metrics.oteljava[IO](config.serviceName, config.metricsPort).use {
          meter =>
            for {
              logger <- Logging.create[IO](tracer, config.serviceName)
              _ <- logger.info(
                Map(
                  "port" -> config.port.toString,
                  "metrics_port" -> config.metricsPort.toString,
                  "inventory_service_base_url" -> config.inventoryServiceBaseUrl
                )
              )("order-service starting")
              _ <- OrderStore.postgres[IO](config.postgres, meter).use {
                store =>
                  HttpClient.resource[IO].use { httpClient =>
                    val tracedClient =
                      ClientTracing.middleware(tracer)(httpClient)
                    val metricClient =
                      ClientMetrics.middleware[IO](meter)(tracedClient)
                    val resilientClient =
                      Resilience.middleware[IO](config.resilience)(
                        logger
                      )(meter)(metricClient)
                    val inventory =
                      InventoryClient[IO](
                        resilientClient,
                        inventoryServiceBaseUri
                      )
                    val docsRoutes = Docs.routes[IO](
                      "Order Service",
                      "1.0",
                      List(
                        OrderRoutes
                          .serverEndpoint[IO](store, inventory, logger),
                        OrderRoutes.getOrderServerEndpoint[IO](store, logger)
                      )
                    )
                    val tracedRoutes =
                      ServerTracing.middleware(tracer)(docsRoutes)
                    val routes =
                      ServerMetrics.middleware[IO](meter)(tracedRoutes)
                    EmberServerBuilder
                      .default[IO]
                      .withHost(host"0.0.0.0")
                      .withPort(port)
                      .withHttpApp(routes.orNotFound)
                      .build
                      .useForever
                  }
              }
            } yield ()
        }
      }
    } yield ()
}
