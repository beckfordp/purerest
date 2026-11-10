// SPDX-License-Identifier: MIT

package inventoryservice

import cats.effect.{Ref, Sync}
import cats.syntax.all._
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

final case class Reservation(id: String, item: String, quantity: Int)

object Reservation {
  implicit val codec: Codec[Reservation] = deriveCodec
}

trait InventoryStore[F[_]] {
  def reserve(item: String, quantity: Int): F[Reservation]
}

object InventoryStore {
  def inMemory[F[_]: Sync]: F[InventoryStore[F]] =
    Ref.of[F, Map[String, Reservation]](Map.empty).map { ref =>
      new InventoryStore[F] {
        def reserve(item: String, quantity: Int): F[Reservation] =
          for {
            id <- Sync[F].delay(java.util.UUID.randomUUID().toString)
            reservation = Reservation(id, item, quantity)
            _ <- ref.update(_ + (id -> reservation))
          } yield reservation
      }
    }
}
