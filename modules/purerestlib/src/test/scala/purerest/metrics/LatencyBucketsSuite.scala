package purerest.metrics

import munit.FunSuite

class LatencyBucketsSuite extends FunSuite {

  test("seconds holds the agreed 5ms-10s boundaries, dense around the SLOs") {
    assertEquals(
      LatencyBuckets.seconds.boundaries,
      Vector(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.15, 0.2, 0.25, 0.3, 0.4,
        0.5, 0.6, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 7.5, 10.0)
    )
  }

  test("seconds boundaries are strictly ascending") {
    val bs = LatencyBuckets.seconds.boundaries
    assert(bs.zip(bs.tail).forall(_ < _), s"not ascending: $bs")
  }
}
