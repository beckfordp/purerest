package purekafka.resilience

import cats.effect.{Async, Ref}
import cats.syntax.all._
import io.github.resilience4j.circuitbreaker.{
  CircuitBreakerConfig => R4jCircuitBreakerConfig
}
import io.github.resilience4j.circuitbreaker.{
  CircuitBreaker => R4jCircuitBreaker
}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{Counter, Gauge, Meter}

import java.time.{Duration => JDuration}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NoStackTrace

/** Config for [[CircuitBreaker.middleware]]. Same shape and semantics as
  * `purerest.resilience.CircuitBreaker`'s own `CircuitBreakerConfig`.
  */
final case class CircuitBreakerConfig(
    failureThreshold: Int,
    resetTimeout: FiniteDuration
)

/** Raised when a call is rejected because the circuit breaker is open — never
  * resilience4j's own `CallNotPermittedException`, same "no third-party types
  * in the public API" stance as the HTTP version.
  */
case object CircuitBreakerOpen
    extends RuntimeException("Circuit breaker is open")
    with NoStackTrace

/** Wraps resilience4j-circuitbreaker's core, non-reactive `CircuitBreaker`
  * engine as a generic [[Guard]], simpler than
  * `purerest.resilience.CircuitBreaker`: that HTTP version needs an
  * `isFailureResult` predicate because a 5xx `Response` is a normal *returned
  * value*, indistinguishable from success without one. A Kafka producer call
  * has no such case — any raised error is the failure signal, any returned `A`
  * is success — so this uses resilience4j's `onSuccess`/`onError` hooks
  * directly instead of the HTTP version's value-inspecting `onResult`.
  */
object CircuitBreaker {

  /** The circuit-breaking [[Guard]] described above. Constructs the underlying
    * resilience4j `CircuitBreaker` engine exactly once — it is genuinely
    * stateful (failure counts, open/closed/half-open) and must be the same
    * instance across every call this [[Guard]] wraps, same as the HTTP
    * version's single `R4jCircuitBreaker.of(...)` per `middleware` call.
    */
  def middleware[F[_]: Async](
      config: CircuitBreakerConfig
  )(meter: Meter[F]): Guard[F] = {
    val stateTransitionsCounterRef: Ref[F, Option[Counter[F, Long]]] =
      Ref.unsafe(None)
    val callsRejectedCounterRef: Ref[F, Option[Counter[F, Long]]] =
      Ref.unsafe(None)
    val stateGaugeRef: Ref[F, Option[Gauge[F, Long]]] = Ref.unsafe(None)

    def memoized[A](
        ref: Ref[F, Option[A]],
        create: F[A]
    ): F[A] =
      ref.get.flatMap {
        case Some(instrument) => instrument.pure[F]
        case None => create.flatTap(instrument => ref.set(Some(instrument)))
      }

    def memoizedCounter(
        ref: Ref[F, Option[Counter[F, Long]]],
        name: String
    ): F[Counter[F, Long]] =
      memoized(ref, meter.counter[Long](name).create)

    def recordTransition(
        before: R4jCircuitBreaker.State,
        after: R4jCircuitBreaker.State
    ): F[Unit] =
      if (before == after) Async[F].unit
      else
        memoizedCounter(
          stateTransitionsCounterRef,
          "purekafka.circuit_breaker.state_transitions"
        ).flatMap(
          _.inc(
            Attribute("from_state", before.name),
            Attribute("to_state", after.name)
          )
        )

    def recordRejection: F[Unit] =
      memoizedCounter(
        callsRejectedCounterRef,
        "purekafka.circuit_breaker.calls_rejected"
      ).flatMap(_.inc())

    def recordState(state: R4jCircuitBreaker.State): F[Unit] =
      memoized(
        stateGaugeRef,
        meter.gauge[Long]("purekafka.circuit_breaker.state").create
      )
        .flatMap { gauge =>
          List(
            R4jCircuitBreaker.State.CLOSED,
            R4jCircuitBreaker.State.OPEN,
            R4jCircuitBreaker.State.HALF_OPEN
          ).traverse_ { candidate =>
            gauge.record(
              if (candidate == state) 1L else 0L,
              Attribute("state", candidate.name)
            )
          }
        }

    val r4jConfig = R4jCircuitBreakerConfig
      .custom()
      .slidingWindowType(R4jCircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
      .slidingWindowSize(config.failureThreshold)
      .minimumNumberOfCalls(config.failureThreshold)
      .failureRateThreshold(100.0f)
      .waitDurationInOpenState(JDuration.ofMillis(config.resetTimeout.toMillis))
      .permittedNumberOfCallsInHalfOpenState(1)
      .build()
    val breaker = R4jCircuitBreaker.of("purekafka", r4jConfig)

    new Guard[F] {
      def apply[A](fa: F[A]): F[A] =
        Async[F].delay(breaker.getState).flatTap(recordState).flatMap {
          stateBefore =>
            Async[F].delay(breaker.tryAcquirePermission()).flatMap {
              case false =>
                recordRejection *> Async[F].raiseError[A](CircuitBreakerOpen)
              case true =>
                Async[F].monotonic.flatMap { start =>
                  fa.attempt.flatMap {
                    case Right(a) =>
                      Async[F].monotonic
                        .flatMap(end =>
                          Async[F].delay(
                            breaker
                              .onSuccess(
                                (end - start).toNanos,
                                TimeUnit.NANOSECONDS
                              )
                          ) *> Async[F].delay(breaker.getState)
                        )
                        .flatMap(stateAfter =>
                          recordState(stateAfter) *> recordTransition(
                            stateBefore,
                            stateAfter
                          )
                        )
                        .as(a)
                    case Left(error) =>
                      Async[F].monotonic
                        .flatMap(end =>
                          Async[F].delay(
                            breaker.onError(
                              (end - start).toNanos,
                              TimeUnit.NANOSECONDS,
                              error
                            )
                          ) *> Async[F].delay(breaker.getState)
                        )
                        .flatMap(stateAfter =>
                          recordState(stateAfter) *> recordTransition(
                            stateBefore,
                            stateAfter
                          )
                        ) *> Async[F].raiseError[A](error)
                  }
                }
            }
        }
    }
  }
}
