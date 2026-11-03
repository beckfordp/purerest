package purerest.logging

import org.typelevel.log4cats.StructuredLogger

/** Journey milestone log lines (order placed, reserved, authorized, dispatched,
  * captured, notified — gluon PERF-4.11), one shape across services so Kibana
  * can follow an order by `order_id` or `correlation_id`. Order ids belong here
  * and on spans, never on metric labels.
  */
object Milestones {

  /** Logs `milestone <name>` at info with fields `milestone`, `order_id`,
    * `correlation_id` (when known) and `extra`.
    */
  def log[F[_]](logger: StructuredLogger[F])(
      milestone: String,
      orderId: String,
      correlationId: Option[String],
      extra: Map[String, String] = Map.empty
  ): F[Unit] =
    logger.info(
      Map("milestone" -> milestone, "order_id" -> orderId) ++
        correlationId.map("correlation_id" -> _) ++ extra
    )(s"milestone $milestone")
}
