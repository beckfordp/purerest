// SPDX-License-Identifier: MIT

package purerest.logging

import org.typelevel.log4cats.StructuredLogger

/** Journey milestone log lines (order placed, reserved, authorized, dispatched,
  * captured, notified — gluon PERF-4.11), one shape across services so Kibana
  * can follow an order by `order_id`, and a trace by the `trace_id`/`span_id` a
  * `Logging.traceCorrelated` logger adds. Order ids belong here and on spans,
  * never on metric labels.
  */
object Milestones {

  /** Logs `milestone <name>` at info with fields `milestone`, `order_id` and
    * `extra`.
    */
  def log[F[_]](logger: StructuredLogger[F])(
      milestone: String,
      orderId: String,
      extra: Map[String, String] = Map.empty
  ): F[Unit] =
    logger.info(
      Map("milestone" -> milestone, "order_id" -> orderId) ++ extra
    )(s"milestone $milestone")
}
