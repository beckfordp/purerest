// SPDX-License-Identifier: MIT

package purerest.metrics

import cats.effect.{Async, Resource}
import cats.effect.syntax.all._
import cats.syntax.all._
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.Meter

import scala.concurrent.duration.SECONDS

/** Connection-pool metrics for any pool handed out as a `Resource[F, A]` —
  * skunk's `Session.pooled` exposes no in-use/waiting counts of its own (gluon
  * PERF-4.3). Generic in `A`, so purerestlib takes no skunk dependency.
  *
  * Records OTel database semantic-convention instruments, all tagged
  * `db.client.connection.pool.name`:
  *   - `db.client.connection.wait_time` (s): time to acquire a connection
  *   - `db.client.connection.count{db.client.connection.state=used}`:
  *     connections currently held
  *   - `db.client.connection.pending_requests`: callers waiting to acquire
  */
object PoolMetrics {

  /** Wraps `pool` so every acquisition is measured as above. Instruments are
    * created once, here, and reused across acquisitions.
    */
  def instrument[F[_]: Async, A](meter: Meter[F], poolName: String)(
      pool: Resource[F, A]
  ): F[Resource[F, A]] = {
    val poolAttr = Attribute("db.client.connection.pool.name", poolName)
    val usedAttrs =
      List(poolAttr, Attribute("db.client.connection.state", "used"))
    (
      meter
        .histogram[Double]("db.client.connection.wait_time")
        .withUnit("s")
        .withExplicitBucketBoundaries(LatencyBuckets.seconds)
        .create,
      meter.upDownCounter[Long]("db.client.connection.count").create,
      meter
        .upDownCounter[Long]("db.client.connection.pending_requests")
        .create
    ).mapN { (waitTime, used, pending) =>
      // Pending covers acquisition only; used covers the time the connection
      // is held, released even if `use` fails or is cancelled.
      Resource.applyFull[F, A] { poll =>
        for {
          _ <- pending.inc(poolAttr)
          start <- Async[F].monotonic
          allocated <- poll(pool.allocated).guarantee(pending.dec(poolAttr))
          (a, releaseConnection) = allocated
          end <- Async[F].monotonic
          _ <- waitTime.record((end - start).toUnit(SECONDS), poolAttr)
          _ <- used.inc(usedAttrs)
        } yield a -> ((_: Resource.ExitCase) =>
          releaseConnection.guarantee(used.dec(usedAttrs))
        )
      }
    }
  }
}
