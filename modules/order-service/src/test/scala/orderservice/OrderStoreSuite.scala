// SPDX-License-Identifier: MIT

package orderservice

import cats.effect.IO
import munit.CatsEffectSuite

class OrderStoreSuite extends CatsEffectSuite {

  test(
    "create returns an order for the requested item, quantity, and reservation"
  ) {
    for {
      store <- OrderStore.inMemory[IO]
      order <- store.create("widget", 2, "reservation-1", 2)
    } yield {
      assertEquals(order.item, "widget")
      assertEquals(order.quantity, 2)
      assertEquals(order.status, "reserved")
      assertEquals(order.reservationId, "reservation-1")
      assertEquals(order.reservedQuantity, 2)
      assert(order.id.nonEmpty)
    }
  }

  test("get returns the persisted order") {
    for {
      store <- OrderStore.inMemory[IO]
      created <- store.create("widget", 2, "reservation-1", 2)
      found <- store.get(created.id)
    } yield assertEquals(found, Some(created))
  }

  test("get returns None for an unknown id") {
    for {
      store <- OrderStore.inMemory[IO]
      found <- store.get("unknown-id")
    } yield assertEquals(found, None)
  }

  test("create produces distinct ids across calls") {
    for {
      store <- OrderStore.inMemory[IO]
      first <- store.create("widget", 1, "reservation-1", 1)
      second <- store.create("widget", 1, "reservation-2", 1)
    } yield assertNotEquals(first.id, second.id)
  }
}
