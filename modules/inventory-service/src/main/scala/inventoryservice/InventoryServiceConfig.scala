package inventoryservice

import cats.effect.Sync
import pureconfig.{ConfigReader, ConfigSource}

given ConfigReader[InducedFailureConfig] = ConfigReader.derived

final case class InventoryServiceConfig(
    port: Int,
    metricsPort: Int,
    serviceName: String,
    inducedFailure: InducedFailureConfig
) derives ConfigReader

object InventoryServiceConfig {
  def load[F[_]: Sync]: F[InventoryServiceConfig] =
    Sync[F].delay(ConfigSource.default.loadOrThrow[InventoryServiceConfig])
}
