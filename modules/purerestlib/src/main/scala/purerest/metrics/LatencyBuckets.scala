package purerest.metrics

import org.typelevel.otel4s.metrics.BucketBoundaries

/** Explicit bucket boundaries for latency histograms recorded in seconds.
  *
  * Without them a histogram gets the OTel SDK defaults (`0, 5, 10, 25 …
  * 10000`), which are sized for milliseconds: nearly every request lands in the
  * 0–5s bucket and p50/p95/p99 are meaningless. These run 5ms–10s, dense from
  * 250ms to 2.5s where the latency SLOs sit.
  *
  * Used by [[ServerMetrics]] and [[ClientMetrics]]; services apply the same
  * boundaries to their own `db.client.operation.duration` histograms via
  * `withExplicitBucketBoundaries(LatencyBuckets.seconds)`.
  */
object LatencyBuckets {

  val seconds: BucketBoundaries = BucketBoundaries(
    0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.15, 0.2, 0.25, 0.3, 0.4, 0.5, 0.6,
    0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 7.5, 10.0
  )
}
