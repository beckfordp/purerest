package purerest.metrics

import cats.effect.{IO, Resource}
import io.opentelemetry.sdk.metrics.data.MetricData
import munit.CatsEffectSuite

import scala.jdk.CollectionConverters._

class PoolMetricsSuite extends CatsEffectSuite {

  private def longSum(metrics: List[MetricData], name: String): Long =
    metrics
      .find(_.getName == name)
      .map(_.getLongSumData.getPoints.asScala.map(_.getValue).sum)
      .getOrElse(0L)

  private def waitCount(metrics: List[MetricData]): Long =
    metrics
      .find(_.getName == "db.client.connection.wait_time")
      .map(_.getHistogramData.getPoints.asScala.map(_.getCount).sum)
      .getOrElse(0L)

  test("counts a connection as used while held and records wait time") {
    Metrics.test[IO]("pool-test").use { tm =>
      for {
        pool <- PoolMetrics.instrument[IO, String](tm.meter, "orders")(
          Resource.pure("session")
        )
        during <- pool.use(_ => tm.collectMetrics)
        after <- tm.collectMetrics
      } yield {
        assertEquals(longSum(during, "db.client.connection.count"), 1L)
        assertEquals(longSum(after, "db.client.connection.count"), 0L)
        assertEquals(
          longSum(after, "db.client.connection.pending_requests"),
          0L
        )
        assertEquals(waitCount(after), 1L)
        val attrs = after
          .find(_.getName == "db.client.connection.count")
          .get
          .getLongSumData
          .getPoints
          .asScala
          .head
          .getAttributes
          .asMap
          .asScala
          .map((k, v) => k.getKey -> v.toString)
        assertEquals(
          attrs.get("db.client.connection.pool.name"),
          Some("orders")
        )
        assertEquals(attrs.get("db.client.connection.state"), Some("used"))
      }
    }
  }

  test("counts a request as pending while acquisition is in progress") {
    Metrics.test[IO]("pool-test").use { tm =>
      for {
        gate <- cats.effect.Deferred[IO, Unit]
        pool <- PoolMetrics.instrument[IO, Unit](tm.meter, "orders")(
          Resource.eval(gate.get)
        )
        fiber <- pool.use_.start
        _ <- IO.sleep(scala.concurrent.duration.DurationInt(50).millis)
        waiting <- tm.collectMetrics
        _ <- gate.complete(())
        _ <- fiber.joinWithNever
        after <- tm.collectMetrics
      } yield {
        assertEquals(
          longSum(waiting, "db.client.connection.pending_requests"),
          1L
        )
        assertEquals(
          longSum(after, "db.client.connection.pending_requests"),
          0L
        )
      }
    }
  }

  test("releases used and pending counts when use or acquire fails") {
    Metrics.test[IO]("pool-test").use { tm =>
      for {
        ok <- PoolMetrics.instrument[IO, Unit](tm.meter, "p")(Resource.unit)
        bad <- PoolMetrics.instrument[IO, Unit](tm.meter, "p")(
          Resource.eval(IO.raiseError(new RuntimeException("acquire")))
        )
        _ <- ok.use(_ => IO.raiseError(new RuntimeException("use"))).attempt
        _ <- bad.use_.attempt
        after <- tm.collectMetrics
      } yield {
        assertEquals(longSum(after, "db.client.connection.count"), 0L)
        assertEquals(
          longSum(after, "db.client.connection.pending_requests"),
          0L
        )
      }
    }
  }
}
