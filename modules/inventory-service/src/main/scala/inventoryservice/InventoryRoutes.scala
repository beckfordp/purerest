// SPDX-License-Identifier: MIT

package inventoryservice

import cats.effect.{Async, Ref}
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

import scala.concurrent.duration.{Duration, FiniteDuration, MILLISECONDS}

/** Deliberately induced failure/latency, so purerest's resilience combinators
  * can be exercised end-to-end against a real flaky service instead of only
  * against stub clients in unit tests. `failureRate` is a 0.0-1.0 probability
  * of returning a 500 for a given request; `delay` is added latency before
  * responding. Both default to off/zero so normal runs are unaffected.
  */
final case class InducedFailureConfig(
    failureRate: Double,
    delay: FiniteDuration
)

object InducedFailureConfig {
  val disabled: InducedFailureConfig =
    InducedFailureConfig(failureRate = 0.0, delay = Duration.Zero)

  /** Rejects a `failureRate` outside `[0.0, 1.0]` or a negative `delayMs`,
    * rather than silently clamping — an invalid runtime tweak should surface
    * immediately to whoever's driving the experiment, not distort it quietly.
    */
  def validated(
      failureRate: Double,
      delayMs: Long
  ): Either[InducedFailureConfigError, InducedFailureConfig] =
    if (!(failureRate >= 0.0 && failureRate <= 1.0) || delayMs < 0)
      Left(InvalidInducedFailureConfig)
    else
      Right(
        InducedFailureConfig(failureRate, FiniteDuration(delayMs, MILLISECONDS))
      )
}

sealed trait InducedFailureConfigError
case object InvalidInducedFailureConfig extends InducedFailureConfigError

sealed trait ReserveError
case object InducedFailureTriggered extends ReserveError

/** Wire format for `/admin/induced-failure` — `InducedFailureConfig`'s
  * `FiniteDuration` has no natural JSON shape, so this DTO exposes the delay as
  * plain milliseconds instead, mirroring `OrderResponse`'s domain-to-DTO
  * pattern in order-service.
  */
final case class InducedFailureView(failureRate: Double, delayMs: Long)

object InducedFailureView {
  implicit val codec: Codec[InducedFailureView] = deriveCodec

  def apply(config: InducedFailureConfig): InducedFailureView =
    InducedFailureView(config.failureRate, config.delay.toMillis)
}

final case class ErrorResponse(error: String)

object ErrorResponse {
  implicit val codec: Codec[ErrorResponse] = deriveCodec
}

object InventoryRoutes {

  private val reserveErrorOutput: EndpointOutput[ReserveError] =
    statusCode(StatusCode.InternalServerError)
      .and(jsonBody[ErrorResponse])
      .map[ReserveError](_ => InducedFailureTriggered)(_ =>
        ErrorResponse("Induced failure")
      )

  private val reserveEndpoint
      : PublicEndpoint[ReserveRequest, ReserveError, Reservation, Any] =
    endpoint.post
      .in("inventory" / "reserve")
      .in(jsonBody[ReserveRequest])
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[Reservation])
      .errorOut(reserveErrorOutput)

  private val getInducedFailureEndpoint
      : PublicEndpoint[Unit, Unit, InducedFailureView, Any] =
    endpoint.get
      .in("admin" / "induced-failure")
      .out(jsonBody[InducedFailureView])

  private val invalidInducedFailureOutput
      : EndpointOutput[InducedFailureConfigError] =
    statusCode(StatusCode.BadRequest)
      .and(jsonBody[ErrorResponse])
      .map[InducedFailureConfigError](_ => InvalidInducedFailureConfig)(_ =>
        ErrorResponse(
          "failureRate must be within [0.0, 1.0] and delayMs must be >= 0"
        )
      )

  private val patchInducedFailureEndpoint: PublicEndpoint[
    InducedFailureView,
    InducedFailureConfigError,
    InducedFailureView,
    Any
  ] =
    endpoint.patch
      .in("admin" / "induced-failure")
      .in(jsonBody[InducedFailureView])
      .out(jsonBody[InducedFailureView])
      .errorOut(invalidInducedFailureOutput)

  private def maybeInduceFailure[F[_]: Async](
      config: InducedFailureConfig,
      logger: StructuredLogger[F],
      item: String,
      quantity: Int
  ): F[Either[ReserveError, Unit]] =
    Async[F].sleep(config.delay) *>
      (if (config.failureRate <= 0.0) Async[F].pure(Right(()))
       else
         Async[F]
           .delay(scala.util.Random.nextDouble() < config.failureRate)
           .flatMap {
             case true =>
               logger
                 .warn(
                   Map("item" -> item, "quantity" -> quantity.toString)
                 )("Induced failure triggered")
                 .as(Left(InducedFailureTriggered))
             case false => Async[F].pure(Right(()))
           })

  def reserveServerEndpoint[F[_]: Async](
      store: InventoryStore[F],
      logger: StructuredLogger[F],
      configRef: Ref[F, InducedFailureConfig]
  ): ServerEndpoint[Any, F] =
    reserveEndpoint.serverLogic[F] { req =>
      for {
        _ <- logger.info(
          Map(
            "method" -> "POST",
            "path" -> "/inventory/reserve",
            "item" -> req.item,
            "quantity" -> req.quantity.toString
          )
        )("Received request")
        induced <- configRef.get
        outcome <- maybeInduceFailure[F](
          induced,
          logger,
          req.item,
          req.quantity
        )
        result <- outcome match {
          case Left(error) => Async[F].pure(Left(error))
          case Right(_)    =>
            // No error-path logging here: InventoryStore.reserve is an unconditional
            // in-memory write that can't fail (see InventoryStore.inMemory) — nothing
            // to catch. order-service's InventoryClient.reserve is the real,
            // catchable failure mode for this call path (see OrderRoutes.scala).
            for {
              reservation <- store.reserve(req.item, req.quantity)
              _ <- logger.info(
                Map(
                  "reservation_id" -> reservation.id,
                  "item" -> reservation.item,
                  "quantity" -> reservation.quantity.toString
                )
              )("Request completed")
            } yield Right(reservation)
        }
      } yield result
    }

  def getInducedFailureServerEndpoint[F[_]: Async](
      configRef: Ref[F, InducedFailureConfig]
  ): ServerEndpoint[Any, F] =
    getInducedFailureEndpoint.serverLogicSuccess[F] { _ =>
      configRef.get.map(InducedFailureView(_))
    }

  def patchInducedFailureServerEndpoint[F[_]: Async](
      configRef: Ref[F, InducedFailureConfig]
  ): ServerEndpoint[Any, F] =
    patchInducedFailureEndpoint.serverLogic[F] { view =>
      InducedFailureConfig.validated(view.failureRate, view.delayMs) match {
        case Left(error)   => Async[F].pure(Left(error))
        case Right(config) =>
          configRef.set(config).as(Right(InducedFailureView(config)))
      }
    }

  def routes[F[_]: Async](
      store: InventoryStore[F],
      logger: StructuredLogger[F],
      configRef: Ref[F, InducedFailureConfig]
  ): HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(
      List(
        reserveServerEndpoint(store, logger, configRef),
        getInducedFailureServerEndpoint(configRef),
        patchInducedFailureServerEndpoint(configRef)
      )
    )
}
