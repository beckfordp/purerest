package orderservice

import cats.effect.{Async, Ref, Resource, Sync}
import cats.effect.std.Console
import cats.syntax.all._
import fs2.io.net.Network
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.Meter
import purerest.metrics.LatencyBuckets
import skunk.Session
import skunk.codec.all._
import skunk.implicits._

import java.time.OffsetDateTime
import java.util.UUID
import scala.concurrent.duration.SECONDS

final case class Order(
    id: String,
    item: String,
    quantity: Int,
    status: String,
    reservationId: String,
    reservedQuantity: Int,
    createdAt: java.time.Instant
)

trait OrderStore[F[_]] {
  def create(
      item: String,
      quantity: Int,
      reservationId: String,
      reservedQuantity: Int
  ): F[Order]
  def get(id: String): F[Option[Order]]
}

object OrderStore {

  private val defaultStatus = "reserved"

  def inMemory[F[_]: Sync]: F[OrderStore[F]] =
    Ref.of[F, Map[String, Order]](Map.empty).map { ref =>
      new OrderStore[F] {
        def create(
            item: String,
            quantity: Int,
            reservationId: String,
            reservedQuantity: Int
        ): F[Order] =
          for {
            id <- Sync[F].delay(java.util.UUID.randomUUID().toString)
            now <- Sync[F].realTimeInstant
            order = Order(
              id,
              item,
              quantity,
              defaultStatus,
              reservationId,
              reservedQuantity,
              now
            )
            _ <- ref.update(_ + (id -> order))
          } yield order

        def get(id: String): F[Option[Order]] = ref.get.map(_.get(id))
      }
    }

  private val insertOrder
      : skunk.Query[(UUID, String, Int, UUID, Int), OffsetDateTime] =
    sql"""
      INSERT INTO orders (id, item, quantity, reservation_id, reserved_quantity)
      VALUES ($uuid, $text, $int4, $uuid, $int4)
      RETURNING created_at
    """.query(timestamptz)

  private val selectOrder
      : skunk.Query[UUID, (String, Int, String, UUID, Int, OffsetDateTime)] =
    sql"""
      SELECT item, quantity, status, reservation_id, reserved_quantity, created_at
      FROM orders
      WHERE id = $uuid
    """.query(text *: int4 *: text *: uuid *: int4 *: timestamptz)

  def postgres[F[_]: Async: Console: Network](
      config: PostgresConfig,
      meter: Meter[F]
  ): Resource[F, OrderStore[F]] = {
    import org.typelevel.otel4s.trace.Tracer.Implicits.noop
    import org.typelevel.otel4s.metrics.Meter.Implicits.noop
    Session
      .Builder[F]
      .withHost(config.host)
      .withPort(config.port)
      .withUserAndPassword(config.user, config.password)
      .withDatabase(config.database)
      .pooled(max = 10)
      .evalMap { pool =>
        meter
          .histogram[Double]("db.client.operation.duration")
          .withUnit("s")
          .withExplicitBucketBoundaries(LatencyBuckets.seconds)
          .create
          .map { histogram =>
            /** Times a Skunk query, recording a `db.client.operation.duration`
              * measurement tagged with `db.system`/`db.operation` (OTel
              * semantic-convention names), plus `error.type` if it fails — this
              * is order-service's only Postgres consumer, so it's instrumented
              * directly here rather than via a new purerest combinator.
              */
            def timed[A](operation: String)(fa: F[A]): F[A] =
              for {
                start <- Async[F].monotonic
                result <- fa.attempt
                end <- Async[F].monotonic
                outcomeAttributes = result match {
                  case Right(_)    => Nil
                  case Left(error) =>
                    List(Attribute("error.type", error.getClass.getName))
                }
                _ <- histogram.record(
                  (end - start).toUnit(SECONDS),
                  List(
                    Attribute("db.system", "postgresql"),
                    Attribute("db.operation", operation)
                  ) ++ outcomeAttributes
                )
                a <- result.liftTo[F]
              } yield a

            new OrderStore[F] {
              def create(
                  item: String,
                  quantity: Int,
                  reservationId: String,
                  reservedQuantity: Int
              ): F[Order] =
                for {
                  id <- Sync[F].delay(UUID.randomUUID())
                  reservationUuid <- Sync[F].delay(
                    UUID.fromString(reservationId)
                  )
                  createdAt <- timed("insert") {
                    pool.use { session =>
                      session
                        .prepare(insertOrder)
                        .flatMap(
                          _.unique(
                            (
                              id,
                              item,
                              quantity,
                              reservationUuid,
                              reservedQuantity
                            )
                          )
                        )
                    }
                  }
                } yield Order(
                  id.toString,
                  item,
                  quantity,
                  defaultStatus,
                  reservationId,
                  reservedQuantity,
                  createdAt.toInstant
                )

              def get(id: String): F[Option[Order]] =
                scala.util.Try(UUID.fromString(id)).toOption match {
                  case None       => Sync[F].pure(None)
                  case Some(uuid) =>
                    for {
                      row <- timed("select") {
                        pool.use { session =>
                          session.prepare(selectOrder).flatMap(_.option(uuid))
                        }
                      }
                    } yield row.map {
                      case (
                            item,
                            quantity,
                            status,
                            reservationUuid,
                            reservedQuantity,
                            createdAt
                          ) =>
                        Order(
                          id,
                          item,
                          quantity,
                          status,
                          reservationUuid.toString,
                          reservedQuantity,
                          createdAt.toInstant
                        )
                    }
                }
            }
          }
      }
  }
}
