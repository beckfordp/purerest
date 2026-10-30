package orderservice

import cats.effect.IO
import cats.syntax.all._
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName
import org.typelevel.otel4s.metrics.Meter
import purerest.metrics.{LatencyBuckets, Metrics}

import scala.jdk.CollectionConverters._

class OrderStorePostgresSuite extends CatsEffectSuite with TestContainerForAll {

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(dockerImageName =
      DockerImageName.parse("postgres:16-alpine")
    )

  private def configFor(postgres: PostgreSQLContainer): PostgresConfig =
    PostgresConfig(
      host = postgres.host,
      port = postgres.mappedPort(5432),
      database = postgres.databaseName,
      user = postgres.username,
      password = postgres.password
    )

  test("create persists an order with its reservation and returns it") {
    withContainers { postgres =>
      val config = configFor(postgres)
      val reservationId = java.util.UUID.randomUUID().toString
      Migrations.run[IO](config) *> OrderStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          store.create("widget", 2, reservationId, 2).map { order =>
            assertEquals(order.item, "widget")
            assertEquals(order.quantity, 2)
            assertEquals(order.status, "reserved")
            assertEquals(order.reservationId, reservationId)
            assertEquals(order.reservedQuantity, 2)
            assert(order.id.nonEmpty)
          }
        }
    }
  }

  test("create produces distinct ids across calls") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations
        .run[IO](config) *> OrderStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          for {
            first <- store
              .create("widget", 1, java.util.UUID.randomUUID().toString, 1)
            second <- store.create(
              "widget",
              1,
              java.util.UUID.randomUUID().toString,
              1
            )
          } yield assertNotEquals(first.id, second.id)
        }
    }
  }

  test("get returns the persisted order, including its reservation") {
    withContainers { postgres =>
      val config = configFor(postgres)
      val reservationId = java.util.UUID.randomUUID().toString
      Migrations.run[IO](config) *> OrderStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          for {
            created <- store.create("widget", 3, reservationId, 3)
            found <- store.get(created.id)
          } yield assertEquals(found, Some(created))
        }
    }
  }

  test("get returns None for an unknown id") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> OrderStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          store
            .get(java.util.UUID.randomUUID().toString)
            .map(assertEquals(_, None))
        }
    }
  }

  test("get returns None for a malformed (non-UUID) id") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> OrderStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          store.get("not-a-uuid").map(assertEquals(_, None))
        }
    }
  }

  test(
    "create and get each record a db.client.operation.duration measurement, tagged by operation"
  ) {
    withContainers { postgres =>
      val config = configFor(postgres)
      Metrics.test[IO]("order-store-postgres-metrics-test").use { testMeter =>
        Migrations.run[IO](config) *> OrderStore
          .postgres[IO](config, testMeter.meter)
          .use { store =>
            for {
              created <- store.create(
                "widget",
                1,
                java.util.UUID.randomUUID().toString,
                1
              )
              _ <- store.get(created.id)
              metrics <- testMeter.collectMetrics
            } yield {
              val data =
                metrics.find(_.getName == "db.client.operation.duration")
              assert(
                data.isDefined,
                s"expected a db.client.operation.duration series, got: $metrics"
              )
              val dbOperationKey =
                io.opentelemetry.api.common.AttributeKey
                  .stringKey("db.operation")
              val operations = data.get.getHistogramData.getPoints.asScala
                .flatMap(point =>
                  Option(point.getAttributes.get(dbOperationKey))
                )
                .toSet
              assert(
                operations.contains("insert") && operations.contains("select"),
                s"expected db.operation attributes for both insert and select, got: $operations"
              )
              data.get.getHistogramData.getPoints.asScala.foreach { point =>
                assertEquals(
                  point.getBoundaries.asScala.map(_.doubleValue).toVector,
                  LatencyBuckets.seconds.boundaries
                )
              }
            }
          }
      }
    }
  }

  test(
    "a failing query records a db.client.operation.duration measurement tagged with error.type"
  ) {
    withContainers { postgres =>
      // Port 1 is a privileged port nothing binds to in these tests; unlike
      // `mappedPort(5432) + 1`, it can't collide with another concurrently-running
      // Testcontainers Postgres instance's dynamically assigned port.
      val unreachableConfig = configFor(postgres).copy(port = 1)
      Metrics.test[IO]("order-store-postgres-metrics-test").use { testMeter =>
        OrderStore.postgres[IO](unreachableConfig, testMeter.meter).use {
          store =>
            for {
              result <- store
                .create("widget", 1, java.util.UUID.randomUUID().toString, 1)
                .attempt
              metrics <- testMeter.collectMetrics
            } yield {
              assert(
                result.isLeft,
                s"expected the connection failure to propagate, got: $result"
              )
              val data =
                metrics.find(_.getName == "db.client.operation.duration")
              assert(
                data.isDefined,
                s"expected a db.client.operation.duration series, got: $metrics"
              )
              val errorTypeKey =
                io.opentelemetry.api.common.AttributeKey.stringKey("error.type")
              val hasErrorAttribute =
                data.get.getHistogramData.getPoints.asScala
                  .exists(point =>
                    Option(point.getAttributes.get(errorTypeKey)).isDefined
                  )
              assert(
                hasErrorAttribute,
                s"expected a point tagged with error.type, got: ${data.get.getHistogramData.getPoints}"
              )
            }
        }
      }
    }
  }
}
