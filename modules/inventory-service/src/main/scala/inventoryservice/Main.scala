package inventoryservice

import cats.effect.{IO, IOApp, Ref}
import com.comcast.ip4s._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import purerest.docs.Docs
import purerest.logging.Logging
import purerest.metrics.{Metrics, ServerMetrics}
import purerest.tracing.{ServerTracing, Tracing}

object Main extends IOApp.Simple {

  val run: IO[Unit] =
    for {
      config <- InventoryServiceConfig.load[IO]
      port <- IO.fromOption(Port.fromInt(config.port))(
        new IllegalArgumentException(
          s"Invalid inventory-service port: ${config.port}"
        )
      )
      _ <- Tracing.console[IO](config.serviceName).use { tracer =>
        Metrics.oteljava[IO](config.serviceName, config.metricsPort).use {
          meter =>
            for {
              logger <- Logging.create[IO](tracer, config.serviceName)
              _ <- logger.info(
                Map(
                  "port" -> config.port.toString,
                  "metrics_port" -> config.metricsPort.toString,
                  "induced_failure_rate" -> config.inducedFailure.failureRate.toString,
                  "induced_delay_ms" -> config.inducedFailure.delay.toMillis.toString
                )
              )("inventory-service starting")
              store <- InventoryStore.inMemory[IO]
              inducedFailureRef <- Ref.of[IO, InducedFailureConfig](
                config.inducedFailure
              )
              docsRoutes = Docs.routes[IO](
                "Inventory Service",
                "1.0",
                List(
                  InventoryRoutes
                    .reserveServerEndpoint[IO](
                      store,
                      logger,
                      inducedFailureRef
                    ),
                  InventoryRoutes
                    .getInducedFailureServerEndpoint[IO](inducedFailureRef),
                  InventoryRoutes
                    .patchInducedFailureServerEndpoint[IO](inducedFailureRef)
                )
              )
              tracedRoutes = ServerTracing.middleware(tracer)(docsRoutes)
              routes = ServerMetrics.middleware[IO](meter)(tracedRoutes)
              _ <- EmberServerBuilder
                .default[IO]
                .withHost(host"0.0.0.0")
                .withPort(port)
                .withHttpApp(routes.orNotFound)
                .build
                .useForever
            } yield ()
        }
      }
    } yield ()
}
