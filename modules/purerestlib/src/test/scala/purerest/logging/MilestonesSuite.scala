// SPDX-License-Identifier: MIT

package purerest.logging

import cats.effect.IO
import munit.CatsEffectSuite
import org.typelevel.log4cats.testing.StructuredTestingLogger

class MilestonesSuite extends CatsEffectSuite {

  test("logs an info line with milestone, order_id and extra fields") {
    val logger = StructuredTestingLogger.impl[IO]()
    for {
      _ <- Milestones.log(logger)(
        "order_placed",
        "o-1",
        Map("total" -> "10")
      )
      logged <- logger.logged
    } yield assertEquals(
      logged.toList,
      List(
        StructuredTestingLogger.INFO(
          "milestone order_placed",
          None,
          Map(
            "milestone" -> "order_placed",
            "order_id" -> "o-1",
            "total" -> "10"
          )
        )
      )
    )
  }

  test("extra fields are optional") {
    val logger = StructuredTestingLogger.impl[IO]()
    for {
      _ <- Milestones.log(logger)("order_dispatched", "o-2")
      logged <- logger.logged
    } yield assertEquals(
      logged.toList.map(_.ctx),
      List(Map("milestone" -> "order_dispatched", "order_id" -> "o-2"))
    )
  }
}
