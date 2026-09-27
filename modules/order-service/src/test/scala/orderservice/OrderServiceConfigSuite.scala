package orderservice

import cats.effect.IO
import munit.CatsEffectSuite
import pureconfig.ConfigSource
import purerest.resilience.{CircuitBreakerConfig, ResilienceConfig, RetryConfig}

import scala.concurrent.duration._

class OrderServiceConfigSuite extends CatsEffectSuite {

  private val validHocon =
    """
      |port = 8080
      |metrics-port = 9090
      |service-name = "order-service"
      |inventory-service-base-url = "http://localhost:8081"
      |postgres {
      |  host = "localhost"
      |  port = 5432
      |  database = "orders"
      |  user = "orders"
      |  password = "orders"
      |}
      |resilience {
      |  retry {
      |    max-retries = 3
      |    base-delay = 100ms
      |  }
      |  circuit-breaker {
      |    failure-threshold = 5
      |    reset-timeout = 30s
      |  }
      |}
      |""".stripMargin

  test("loads a fully-specified config") {
    val result = ConfigSource.string(validHocon).load[OrderServiceConfig]
    assertEquals(
      result,
      Right(
        OrderServiceConfig(
          port = 8080,
          metricsPort = 9090,
          serviceName = "order-service",
          inventoryServiceBaseUrl = "http://localhost:8081",
          postgres = PostgresConfig(
            host = "localhost",
            port = 5432,
            database = "orders",
            user = "orders",
            password = "orders"
          ),
          resilience = ResilienceConfig(
            retry = RetryConfig(maxRetries = 3, baseDelay = 100.millis),
            circuitBreaker =
              CircuitBreakerConfig(failureThreshold = 5, resetTimeout = 30.seconds)
          )
        )
      )
    )
  }

  test("fails to load when a required field is missing") {
    val missingPassword =
      """
        |port = 8080
        |metrics-port = 9090
        |inventory-service-base-url = "http://localhost:8081"
        |postgres {
        |  host = "localhost"
        |  port = 5432
        |  database = "orders"
        |  user = "orders"
        |}
        |""".stripMargin

    assert(ConfigSource.string(missingPassword).load[OrderServiceConfig].isLeft)
  }

  test("load[F] reads the shipped application.conf defaults") {
    OrderServiceConfig.load[IO].map { config =>
      assertEquals(config.port, 8080)
      assertEquals(config.metricsPort, 9090)
      assertEquals(config.serviceName, "order-service")
      assertEquals(config.inventoryServiceBaseUrl, "http://localhost:8081")
      assertEquals(
        config.postgres,
        PostgresConfig("localhost", 5432, "orders", "orders", "orders")
      )
      assertEquals(
        config.resilience,
        ResilienceConfig(
          retry = RetryConfig(maxRetries = 3, baseDelay = 100.millis),
          circuitBreaker =
            CircuitBreakerConfig(failureThreshold = 5, resetTimeout = 30.seconds)
        )
      )
    }
  }
}
