package loadtest

import cats.effect.{IO, Ref}
import cats.syntax.all._
import com.dimafeng.testcontainers.KafkaContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import fs2.Stream
import fs2.kafka._
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.sdk.metrics.data.MetricData
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.otel4s.oteljava.testkit.metrics.MetricsTestkit
import org.typelevel.otel4s.oteljava.testkit.trace.TracesTestkit
import purekafka.consumer.{
  ConsumerLag,
  ConsumerLagConfig,
  DeadLetter,
  StreamSupervisor,
  StreamSupervisorConfig
}
import purekafka.resilience.{CircuitBreakerConfig, Resilience, ResilienceConfig, RetryConfig}
import purekafka.tracing.KafkaTracing

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** `purekafka`'s sustained-load counterpart to `OrderPlacementSimulation`
  * (ADR 0010, Phase 5 of the `purekafka` track) — drives real produce/consume
  * traffic against a live, ephemeral Kafka broker for a sustained window,
  * exercising every combinator built in Phases 1-4 together (not in
  * isolation, unlike their own unit tests), with three induced failures:
  * a poison message (proves `DeadLetter` routing fires), a burst of extra
  * production racing far ahead of steady consumption (proves `ConsumerLag`'s
  * gauge actually climbs, sampled *during* the burst — a single end-of-run
  * snapshot would only ever show the settled, post-drain value; a literal
  * paused-consumer approach was tried first and found not to work — see the
  * note at `burstWindow`'s own definition for why), and an injected stream
  * failure (proves
  * `StreamSupervisor` actually restarts under real Kafka consumer-stream
  * conditions, not just the synthetic `Stream[IO, O]` `StreamSupervisorSuite`
  * already uses).
  *
  * '''Why this is a plain munit suite, not a Gatling `Simulation`''', despite
  * `plan.md`'s task literally saying "a new Gatling simulation": checked
  * Maven Central directly — `io.gatling` ships no Kafka protocol module at
  * all (it has `http`/`jms`/`mqtt`/`grpc`/`jdbc`/`redis`/`graphql`, but no
  * `kafka`). The only third-party options found
  * (`org.galaxio:gatling-kafka-plugin_2.13`, `ru.tinkoff:gatling-kafka-plugin_2.13`,
  * `com.github.mnogu:gatling-kafka_2.12`) are all Scala 2.x-only — this
  * build is Scala 3 throughout — and more fundamentally, their DSL only
  * drives raw Kafka send/receive. That doesn't help here: the actual point
  * of this load test is exercising `purekafka`'s own wrapped combinators
  * (`Resilience`, `StreamSupervisor`, `DeadLetter`, `ConsumerLag`,
  * `KafkaTracing`) under sustained traffic, not Gatling's own Kafka I/O —
  * a Gatling-Kafka plugin would still leave all of that hand-wired on top,
  * same as this suite already does directly.
  *
  * '''"Broker blip" is simulated by a deliberately-injected processing
  * error, not a literal Docker container pause''' — a deliberate choice,
  * not something tried and found lacking: a literal `docker pause` on the
  * Kafka container (via `DockerClientFactory`, the realistic option) risks
  * not reliably exercising `StreamSupervisor`'s restart path at all, since
  * the underlying Kafka Java client has its own internal poll/retry/backoff
  * for a transient broker-unreachable window and may just block and resume
  * silently rather than raising an exception the fs2 consumer stream would
  * ever see — untested here, so stated as a risk, not a confirmed failure
  * mode. A deliberate one-shot exception thrown from inside the real
  * `consumer.stream.evalMap(...)` pipeline — not a synthetic fs2 `Stream`
  * the way `StreamSupervisorSuite` already covers — still proves the
  * restart-with-backoff mechanism engages under genuine Kafka
  * consumer-stream conditions (real `CommittableConsumerRecord`s, real
  * offset commits, the real supervised stream), which is what this phase
  * actually needs to add on top of Phase 2's unit test.
  *
  * '''Verification mechanism''': a dedicated Testcontainers-Kafka
  * integration suite (this file), not a new `scripts/verify-kafka-observability.sh`
  * + docker-compose + Prometheus/Grafana/Kibana stack mirroring
  * `scripts/verify-observability-stack.sh`. That full stack is real,
  * substantial, already-provisioned infrastructure for the *HTTP*
  * resilience story — standing up an equivalent second observability stack
  * just for this one phase would be disproportionate to what `plan.md`'s
  * actual acceptance criterion asks for ("demonstrates all of the above
  * under sustained traffic, not just in unit tests"). This suite satisfies
  * that more directly: a real broker (Testcontainers, not a fake), genuine
  * sustained traffic over tens of seconds, live induced failures mid-run,
  * and assertions against the real otel4s in-memory testkit — the same
  * mechanism every other suite in this module already uses to prove a
  * metric was actually recorded.
  *
  * Run via `sbt loadTest/testOnly loadtest.KafkaResilienceLoadTest`
  * (`loadTest` is deliberately excluded from root's `.aggregate(...)`, so
  * this doesn't run as part of a plain `sbt test`, same as the Gatling
  * simulation's own isolation).
  */
class KafkaResilienceLoadTest extends CatsEffectSuite with TestContainerForAll {

  // testcontainers-java's KafkaContainer compatibility check flipped which
  // image family it expects by default between testcontainers-java 1.21.1
  // (Confluent's cp-kafka) and 2.0.3 (the official apache/kafka images) -
  // the version this build now pins after the testcontainers-scala 0.44.1
  // bump (see build.sbt's testcontainersScalaVersion comment). Using
  // apache/kafka:3.8.0 - the same image+tag gluon's own
  // infra/k8s/local-infra/kafka.yaml already pins, for ecosystem
  // consistency - rather than fighting the check via
  // asCompatibleSubstituteFor.
  override val containerDef: KafkaContainer.Def =
    KafkaContainer.Def(dockerImageName =
      DockerImageName.parse("apache/kafka:3.8.0")
    )

  // munit's plain `munitTimeout` doesn't bound a CatsEffectSuite's IO-based
  // test body - confirmed by hitting its real 30s default despite
  // overriding that one; `munitIOTimeout` (decompiled CatsEffectSuite
  // directly to confirm the actual method name) is the one that matters
  // here, and needs real headroom beyond this test's own internal
  // `.timeoutTo(32.seconds, ...)` plus container start/teardown overhead.
  override val munitIOTimeout: Duration = 2.minutes

  private val topic = "purekafka-loadtest"
  private val groupId = "purekafka-loadtest-group"
  private val poisonMarker = "__POISON__"
  private val blipMarker = "__BLIP__"

  private def sumCounter(metrics: List[MetricData], name: String): Long =
    metrics
      .filter(_.getName == name)
      .flatMap(_.getLongSumData.getPoints.asScala)
      .map(_.getValue)
      .sum

  private def currentGaugeMax(metrics: List[MetricData], name: String): Double =
    metrics
      .filter(_.getName == name)
      .flatMap(_.getDoubleGaugeData.getPoints.asScala)
      .map(_.getValue)
      .maxOption
      .getOrElse(0.0)

  test(
    "sustained produce/consume traffic: DLQ routing fires, lag gauge climbs during a burst, stream supervision restarts"
  ) {
    withContainers { kafka =>
      val bootstrapServers = kafka.bootstrapServers

      (
        MetricsTestkit.inMemory[IO](),
        TracesTestkit.inMemory[IO](
          _.addTextMapPropagators(W3CTraceContextPropagator.getInstance())
        )
      ).tupled.use { case (metricsTestkit, tracesTestkit) =>
        for {
          meter <- metricsTestkit.meterProvider.get("purekafka-loadtest")
          tracer <- tracesTestkit.tracerProvider.get("purekafka-loadtest")
          logger = NoOpLogger[IO]

          // Sampled repeatedly throughout the run (not just once at the
          // end) so the burst window's actual rise is captured — a single
          // end-of-run snapshot would only show the settled, post-drain
          // value.
          maxLagSeenRef <- Ref.of[IO, Double](0.0)

          producerSettings = ProducerSettings[IO, String, String]
            .withBootstrapServers(bootstrapServers)
          publishResilienceConfig = ResilienceConfig(
            retry = RetryConfig(maxRetries = 3, baseDelay = 50.millis),
            circuitBreaker = CircuitBreakerConfig(
              failureThreshold = 10,
              resetTimeout = 2.seconds
            )
          )
          resilientProduce =
            Resilience.middleware[IO](publishResilienceConfig)(logger)(meter)

          dlqResourcePair <- DeadLetter
            .resource[IO, String, String](
              config = RetryConfig(maxRetries = 1, baseDelay = 10.millis),
              publishResilience = publishResilienceConfig,
              producerSettings = producerSettings
            )(logger)(meter)
            .allocated
          dlq = dlqResourcePair._1

          finalMetrics <- KafkaProducer
            .resource(producerSettings)
            .use { producer =>
              def send(key: String, value: String): IO[Unit] =
                for {
                  headers <- KafkaTracing.injectHeaders(tracer)(Headers.empty)
                  record = ProducerRecord(topic, key, value).withHeaders(
                    headers
                  )
                  _ <- resilientProduce(
                    producer.produceOne_(record).flatten.void
                  )
                } yield ()

              val produceLoop: Stream[IO, Unit] =
                Stream
                  .awakeEvery[IO](40.millis)
                  .zipWithIndex
                  .evalMap { case (_, i) =>
                    val value =
                      if (i == 100) poisonMarker
                      else if (i == 300) blipMarker
                      else s"message-$i"
                    send(s"key-$i", value)
                  }
                  .void

              KafkaConsumer
                .resource(
                  ConsumerSettings[IO, String, String]
                    .withBootstrapServers(bootstrapServers)
                    .withGroupId(groupId)
                    .withAutoOffsetReset(AutoOffsetReset.Earliest)
                )
                .evalTap(_.subscribeTo(topic))
                .use { consumer =>
                  def processRecord(
                      record: CommittableConsumerRecord[IO, String, String]
                  ): IO[Unit] = {
                    val key = record.record.key
                    val value = record.record.value
                    val headers = record.record.headers

                    def handle: IO[Unit] = value match {
                      case v if v == blipMarker =>
                        IO.raiseError(
                          new RuntimeException(
                            "simulated broker blip - deliberately crashing the consumer stream"
                          )
                        )
                      case v if v == poisonMarker =>
                        dlq.guard(topic, key, value)(
                          IO.raiseError(
                            new RuntimeException(s"unprocessable message: $v")
                          )
                        )
                      case _ => dlq.guard(topic, key, value)(IO.unit)
                    }

                    KafkaTracing.traced(tracer)(
                      headers,
                      "consume purekafka-loadtest",
                      topic
                    )(
                      handle *> record.offset.commit
                    )
                  }

                  val consumeLoop: Stream[IO, Unit] =
                    StreamSupervisor.supervise[IO, Unit](
                      StreamSupervisorConfig(
                        baseDelay = 200.millis,
                        maxDelay = 2.seconds
                      )
                    )(logger)(meter) {
                      IO.pure(consumer.stream.evalMap(processRecord))
                    }

                  val lagLoop: Stream[IO, Unit] =
                    ConsumerLag.fromConsumer[IO, String, String](
                      ConsumerLagConfig(
                        pollInterval = 1.second,
                        groupId = groupId
                      )
                    )(meter)(consumer)

                  // Samples the gauge's current value every second for the
                  // whole run, tracking the running max — decoupled from
                  // guessing exactly when the burst window's rise peaks.
                  val lagSamplerLoop: Stream[IO, Unit] =
                    Stream.fixedDelay[IO](1.second).evalMap { _ =>
                      metricsTestkit.collectMetrics.flatMap { metrics =>
                        val observed =
                          currentGaugeMax(metrics, "purekafka.consumer.lag")
                        maxLagSeenRef.update(_.max(observed))
                      }
                    }

                  // Makes real lag appear: Kafka's own `records-lag` metric
                  // only updates on an actual FetchResponse for that
                  // partition, so *blocking downstream processing*
                  // (tried first) backpressures fs2-kafka's fetch loop
                  // too — no new fetches happen, so the metric just
                  // freezes at whatever it last was instead of growing.
                  // Racing a burst of extra production far ahead of
                  // steady consumption keeps fetching active throughout
                  // (no backpressure-freeze), so the *next* real fetch
                  // response legitimately reports a widened gap.
                  // Sequenced to start only after the blip (regular-loop
                  // index 300, produced ~12s in) has had time to be
                  // produced AND consumed - the burst's 500 messages land
                  // on the same topic's offsets, so starting it any
                  // earlier pushes everything produced after it (the blip
                  // included) 500 offsets further out, past what the
                  // consumer can reach in this test's budget (confirmed by
                  // hitting exactly that: streamRestarts=0 again once the
                  // burst started at 5s instead of 15s).
                  val burstWindow: IO[Unit] =
                    IO.sleep(15.seconds) *>
                      (1 to 500).toList.traverse_ { n =>
                        send(s"burst-key-$n", s"burst-$n")
                      }

                  for {
                    _ <- List(
                      produceLoop.compile.drain,
                      consumeLoop.compile.drain,
                      lagLoop.compile.drain,
                      lagSamplerLoop.compile.drain,
                      burstWindow
                    ).parSequence_
                      .timeoutTo(32.seconds, IO.unit)
                    result <- metricsTestkit.collectMetrics
                  } yield result
                }
            }

          dlqPublished = sumCounter(finalMetrics, "purekafka.dlq.published")
          streamRestarts =
            sumCounter(finalMetrics, "purekafka.stream.restarts")
          maxLagSeen <- maxLagSeenRef.get
        } yield {
          assert(
            dlqPublished >= 1,
            s"expected at least 1 purekafka.dlq.published, got $dlqPublished"
          )
          assert(
            streamRestarts >= 1,
            s"expected at least 1 purekafka.stream.restarts, got $streamRestarts"
          )
          assert(
            maxLagSeen > 0,
            s"expected purekafka.consumer.lag to have recorded a positive value during the burst window, max observed was $maxLagSeen"
          )
        }
      }
    }
  }
}
