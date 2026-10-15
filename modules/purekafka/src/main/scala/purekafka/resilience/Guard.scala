package purekafka.resilience

/** A reusable guard around a generic `F[A]` call — constructed once (binding
  * whatever config/metrics/stateful engine it needs), then applied to as many
  * different calls as needed over its lifetime. The Kafka-side analogue of
  * `purerest.resilience`'s `Client[F] => Client[F]` middleware shape: Kafka has
  * no single long-lived `Client[F]`-like value to wrap a middleware around, so
  * this plays the same "construct once, reuse many times" role directly over
  * `F[A]` — essential for [[CircuitBreaker]] in particular, whose underlying
  * resilience4j engine is genuinely stateful (failure counts,
  * open/closed/half-open) and must be the *same* instance across calls, not
  * recreated per call.
  */
trait Guard[F[_]] {
  def apply[A](fa: F[A]): F[A]
}
