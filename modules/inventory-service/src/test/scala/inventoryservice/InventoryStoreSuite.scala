// SPDX-License-Identifier: MIT

package inventoryservice

import cats.effect.IO
import munit.CatsEffectSuite

class InventoryStoreSuite extends CatsEffectSuite {

  test("reserve returns a reservation for the requested item and quantity") {
    for {
      store <- InventoryStore.inMemory[IO]
      reservation <- store.reserve("widget", 2)
    } yield {
      assertEquals(reservation.item, "widget")
      assertEquals(reservation.quantity, 2)
      assert(reservation.id.nonEmpty)
    }
  }

  test("reserve produces distinct ids across calls") {
    for {
      store <- InventoryStore.inMemory[IO]
      first <- store.reserve("widget", 1)
      second <- store.reserve("widget", 1)
    } yield assertNotEquals(first.id, second.id)
  }
}
