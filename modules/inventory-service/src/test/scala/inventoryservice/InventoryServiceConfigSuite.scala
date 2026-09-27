package inventoryservice

import cats.effect.IO
import munit.CatsEffectSuite
import pureconfig.ConfigSource

import scala.concurrent.duration._

class InventoryServiceConfigSuite extends CatsEffectSuite {

  private val validHocon =
    """
      |port = 8081
      |metrics-port = 9091
      |service-name = "inventory-service"
      |induced-failure {
      |  failure-rate = 0.3
      |  delay = 50ms
      |}
      |""".stripMargin

  test("loads a fully-specified config") {
    val result = ConfigSource.string(validHocon).load[InventoryServiceConfig]
    assertEquals(
      result,
      Right(
        InventoryServiceConfig(
          port = 8081,
          metricsPort = 9091,
          serviceName = "inventory-service",
          inducedFailure =
            InducedFailureConfig(failureRate = 0.3, delay = 50.millis)
        )
      )
    )
  }

  test("fails to load when a required field is missing") {
    val missingServiceName =
      """
        |port = 8081
        |metrics-port = 9091
        |induced-failure {
        |  failure-rate = 0.0
        |  delay = 0ms
        |}
        |""".stripMargin

    assert(
      ConfigSource.string(missingServiceName).load[InventoryServiceConfig].isLeft
    )
  }

  test("load[F] reads the shipped application.conf defaults") {
    InventoryServiceConfig.load[IO].map { config =>
      assertEquals(config.port, 8081)
      assertEquals(config.metricsPort, 9091)
      assertEquals(config.serviceName, "inventory-service")
      assertEquals(
        config.inducedFailure,
        InducedFailureConfig(failureRate = 0.0, delay = Duration.Zero)
      )
    }
  }
}
