// SPDX-License-Identifier: MIT

package inventoryservice

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

final case class ReserveRequest(item: String, quantity: Int)

object ReserveRequest {
  implicit val codec: Codec[ReserveRequest] = deriveCodec
}
