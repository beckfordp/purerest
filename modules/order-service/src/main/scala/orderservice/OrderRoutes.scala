// SPDX-License-Identifier: MIT

package orderservice

import cats.effect.Async
import cats.syntax.all._
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import org.http4s.HttpRoutes
import org.typelevel.log4cats.StructuredLogger
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

final case class CreateOrderRequest(item: String, quantity: Int)

object CreateOrderRequest {
  implicit val codec: Codec[CreateOrderRequest] = deriveCodec
}

final case class OrderResponse(
    id: String,
    item: String,
    quantity: Int,
    reservationId: String,
    status: String,
    createdAt: java.time.Instant
)

object OrderResponse {
  implicit val codec: Codec[OrderResponse] = deriveCodec

  def apply(order: Order): OrderResponse =
    OrderResponse(
      order.id,
      order.item,
      order.quantity,
      order.reservationId,
      order.status,
      order.createdAt
    )
}

final case class ErrorResponse(error: String)

object ErrorResponse {
  implicit val codec: Codec[ErrorResponse] = deriveCodec
}

sealed trait CreateOrderError
case object InventoryUnavailable extends CreateOrderError

object OrderRoutes {

  private val inventoryUnavailableOutput: EndpointOutput[CreateOrderError] =
    statusCode(StatusCode.ServiceUnavailable)
      .and(jsonBody[ErrorResponse])
      .map[CreateOrderError](_ => InventoryUnavailable)(_ =>
        ErrorResponse("Inventory service unavailable")
      )

  private val createOrderEndpoint: PublicEndpoint[
    CreateOrderRequest,
    CreateOrderError,
    OrderResponse,
    Any
  ] =
    endpoint.post
      .in("orders")
      .in(jsonBody[CreateOrderRequest])
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[OrderResponse])
      .errorOut(inventoryUnavailableOutput)

  private val notFoundOutput: EndpointOutput[OrderError] =
    statusCode(StatusCode.NotFound)
      .and(jsonBody[ErrorResponse])
      .map[OrderError](_ => OrderNotFound)(_ =>
        ErrorResponse("Order not found")
      )

  private val getOrderEndpoint
      : PublicEndpoint[String, OrderError, OrderResponse, Any] =
    endpoint.get
      .in("orders" / path[String]("id"))
      .out(jsonBody[OrderResponse])
      .errorOut(notFoundOutput)

  def serverEndpoint[F[_]: Async](
      store: OrderStore[F],
      inventory: InventoryClient[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    createOrderEndpoint.serverLogic[F] { req =>
      for {
        _ <- logger.info(
          Map(
            "method" -> "POST",
            "path" -> "/orders",
            "item" -> req.item,
            "quantity" -> req.quantity.toString
          )
        )("Received request")
        reservationAttempt <- inventory.reserve(req.item, req.quantity).attempt
        _ <- reservationAttempt match {
          case Left(error) =>
            logger.error(
              Map("item" -> req.item, "quantity" -> req.quantity.toString),
              error
            )(
              "Inventory reservation failed"
            )
          case Right(_) => Async[F].unit
        }
        result <- reservationAttempt match {
          case Left(_)            => Async[F].pure(Left(InventoryUnavailable))
          case Right(reservation) =>
            for {
              order <- store
                .create(
                  req.item,
                  req.quantity,
                  reservation.id,
                  reservation.quantity
                )
                .onError { case error =>
                  logger.error(
                    Map(
                      "item" -> req.item,
                      "quantity" -> req.quantity.toString
                    ),
                    error
                  )(
                    "Persisting the order failed"
                  )
                }
              _ <- logger.info(
                Map(
                  "order_id" -> order.id,
                  "item" -> order.item,
                  "quantity" -> order.quantity.toString,
                  "reservation_id" -> reservation.id
                )
              )("Request completed")
            } yield Right(OrderResponse(order))
        }
      } yield result
    }

  def getOrderServerEndpoint[F[_]: Async](
      store: OrderStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    getOrderEndpoint.serverLogic[F] { id =>
      for {
        _ <- logger.info(
          Map("method" -> "GET", "path" -> s"/orders/$id", "order_id" -> id)
        )(
          "Received request"
        )
        result <- store.get(id).flatMap {
          case Some(order) =>
            logger
              .info(Map("order_id" -> id))("Request completed")
              .as(Right(OrderResponse(order)))
          case None =>
            logger
              .warn(Map("order_id" -> id))("Order not found")
              .as(Left(OrderNotFound))
        }
      } yield result
    }

  def routes[F[_]: Async](
      store: OrderStore[F],
      inventory: InventoryClient[F],
      logger: StructuredLogger[F]
  ): HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(
      List(
        serverEndpoint(store, inventory, logger),
        getOrderServerEndpoint(store, logger)
      )
    )
}
