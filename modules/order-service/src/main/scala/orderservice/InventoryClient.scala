// SPDX-License-Identifier: MIT

package orderservice

import cats.effect.Concurrent
import cats.syntax.all._
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import org.http4s.circe.CirceEntityCodec._
import org.http4s.client.Client
import org.http4s.{Method, Request, Uri}

final case class ReservationView(id: String, item: String, quantity: Int)

object ReservationView {
  implicit val codec: Codec[ReservationView] = deriveCodec
}

private final case class ReserveInventoryRequest(item: String, quantity: Int)

private object ReserveInventoryRequest {
  implicit val codec: Codec[ReserveInventoryRequest] = deriveCodec
}

trait InventoryClient[F[_]] {
  def reserve(item: String, quantity: Int): F[ReservationView]
}

object InventoryClient {
  def apply[F[_]: Concurrent](
      client: Client[F],
      baseUri: Uri
  ): InventoryClient[F] =
    new InventoryClient[F] {
      def reserve(item: String, quantity: Int): F[ReservationView] =
        client.expect[ReservationView](
          Request[F](Method.POST, baseUri / "inventory" / "reserve")
            .withEntity(ReserveInventoryRequest(item, quantity))
        )
    }
}
