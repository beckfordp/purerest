package kafkaharness

import cats.effect.{IO, IOApp, Ref}
import cats.syntax.all._
import com.comcast.ip4s._
import fs2.Stream
import fs2.kafka._
import io.circe.parser.decode
import io.circe.syntax._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.typelevel.log4cats.noop.NoOpLogger
import purerest.metrics.Metrics
import purerest.tracing.Tracing
import purekafka.consumer.{
  ConsumerLag,
  ConsumerLagConfig,
  DeadLetter,
  StreamSupervisor,
  StreamSupervisorConfig
}
import purekafka.docs.{AsyncApiChannel, Docs}
import purekafka.resilience.{
  CircuitBreakerConfig,
  Resilience,
  ResilienceConfig,
  RetryConfig
}
import purekafka.tracing.KafkaTracing

import scala.concurrent.duration._

/** A small, continuously-running fixture app demonstrating every `purekafka`
  * combinator (ADR 0010 in gluon) together under real, sustained Kafka traffic
  * — the Grafana-visible counterpart to `purekafka`'s own Testcontainers-based
  * `KafkaResilienceLoadTest` (which proves the same behavior but tears down
  * after one otel4s-in-memory-testkit assertion run, never exported anywhere a
  * dashboard could show it). This app instead builds real exporters —
  * `purerest.metrics.Metrics.oteljava` (Prometheus scrape endpoint) and
  * `purerest.tracing.Tracing.console` — the exact same helpers
  * `order-service`/`inventory-service`'s own `Main.scala` already use for the
  * HTTP story, reused here rather than reinvented.
  *
  * Self-driving: produces a steady stream of normal messages forever (each
  * produce wrapped in `Resilience.middleware` — genuinely the one place in this
  * whole ecosystem that still exercises it directly, since gluon's ADR 0008
  * outbox pattern removed every other direct-publish call site), consumes via
  * `StreamSupervisor` + `DeadLetter`, with `ConsumerLag` reporting lag and
  * `KafkaTracing` propagating trace context produce -> consume — plus three
  * periodic, automatic failure injections reusing the exact shapes
  * `KafkaResilienceLoadTest` already proved work: a poison message (-> DLQ), a
  * burst of extra production (-> lag climbs), and an injected processing error
  * (-> stream restart).
  *
  * Run via `docker compose --profile observability up -d` (built as the
  * `kafka-harness` Docker image) — see `scripts/verify-kafka-observability.sh`.
  */
object Main extends IOApp.Simple {

  private val bootstrapServers =
    sys.env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092")
  private val metricsPort =
    sys.env.get("METRICS_PORT").flatMap(_.toIntOption).getOrElse(9093)
  private val httpPort =
    sys.env.get("HTTP_PORT").flatMap(_.toIntOption).getOrElse(8080)
  private val serviceName = "kafka-harness"
  private val topic = "kafka-harness-demo"
  private val groupId = "kafka-harness-group"
  private val poisonMarker = "__POISON__"
  private val blipMarker = "__BLIP__"

  val run: IO[Unit] =
    Tracing.console[IO](serviceName).use { tracer =>
      Metrics.oteljava[IO](serviceName, metricsPort).use { meter =>
        val logger = NoOpLogger[IO]

        val producerSettings =
          ProducerSettings[IO, String, String]
            .withBootstrapServers(bootstrapServers)

        val resilientProduce = Resilience.middleware[IO](
          ResilienceConfig(
            retry = RetryConfig(maxRetries = 3, baseDelay = 50.millis),
            circuitBreaker = CircuitBreakerConfig(
              failureThreshold = 10,
              resetTimeout = 2.seconds
            )
          )
        )(logger)(meter)

        DeadLetter
          .resource[IO, String, String](
            config = RetryConfig(maxRetries = 1, baseDelay = 10.millis),
            producerSettings = producerSettings
          )(logger)(meter)
          .use { dlq =>
            KafkaProducer.resource(producerSettings).use { producer =>
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

              // Shared, monotonically increasing counter for normal-message
              // keys/values — kept separate from the poison/blip/burst loops'
              // own key namespaces below so nothing ever collides.
              def counterStream(ref: Ref[IO, Long]): IO[Long] =
                ref.getAndUpdate(_ + 1)

              val normalLoop: Stream[IO, Unit] =
                Stream.eval(Ref.of[IO, Long](0L)).flatMap { ref =>
                  Stream
                    .awakeEvery[IO](100.millis)
                    .evalMap(_ => counterStream(ref))
                    .evalMap(i =>
                      send(s"key-$i", DemoEvent(s"message-$i").asJson.noSpaces)
                    )
                }

              // Every 20s - frequent enough to see a steady purekafka.dlq.published
              // trickle on the dashboard without dominating the normal-message rate.
              val poisonLoop: Stream[IO, Unit] =
                Stream.eval(Ref.of[IO, Long](0L)).flatMap { ref =>
                  Stream
                    .fixedDelay[IO](20.seconds)
                    .evalMap(_ => counterStream(ref))
                    .evalMap(i =>
                      send(
                        s"poison-key-$i",
                        DemoEvent(poisonMarker).asJson.noSpaces
                      )
                    )
                }

              // Every 45s. A given blip *key* is only ever allowed to crash the
              // stream once - unlike KafkaResilienceLoadTest's one-shot run
              // (which ends via a timeout shortly after its single blip fires,
              // so this never mattered there), a continuously-running consumer
              // that re-raises on every restart for the *same* uncommitted
              // offset would crash-loop forever on that one message instead of
              // ever progressing past it. Tracking "have we already blipped on
              // this key" and treating a repeat as a normal (committable)
              // message is what makes this safe to run forever: first
              // encounter crashes (proving StreamSupervisor's restart), the
              // post-restart redelivery of that same message commits normally
              // and the stream moves on.
              val blipLoop: Stream[IO, Unit] =
                Stream.eval(Ref.of[IO, Long](0L)).flatMap { ref =>
                  Stream
                    .fixedDelay[IO](45.seconds)
                    .evalMap(_ => counterStream(ref))
                    .evalMap(i =>
                      send(
                        s"blip-key-$i",
                        DemoEvent(blipMarker).asJson.noSpaces
                      )
                    )
                }

              // Every 90s: makes real lag appear, same reasoning
              // KafkaResilienceLoadTest's own doc comment already worked out -
              // Kafka's `records-lag` metric only updates on an actual
              // FetchResponse, so blocking downstream processing backpressures
              // fs2-kafka's own fetch loop instead of growing lag. A burst of
              // extra production racing ahead of steady consumption keeps
              // fetching active while still widening the gap.
              val burstLoop: Stream[IO, Unit] =
                Stream.eval(Ref.of[IO, Long](0L)).flatMap { ref =>
                  Stream
                    .fixedDelay[IO](90.seconds)
                    .evalMap(_ => counterStream(ref))
                    .evalMap { cycle =>
                      (1 to 300).toList.traverse_ { n =>
                        send(
                          s"burst-key-$cycle-$n",
                          DemoEvent(s"burst-$cycle-$n").asJson.noSpaces
                        )
                      }
                    }
                }

              KafkaConsumer
                .resource(
                  ConsumerSettings[IO, String, String]
                    .withBootstrapServers(bootstrapServers)
                    .withGroupId(groupId)
                    .withAutoOffsetReset(AutoOffsetReset.Earliest)
                )
                .evalTap(_.subscribeTo(topic))
                .use { consumer =>
                  Ref.of[IO, Set[String]](Set.empty).flatMap { blippedKeysRef =>
                    def processRecord(
                        record: CommittableConsumerRecord[IO, String, String]
                    ): IO[Unit] = {
                      val key = record.record.key
                      val value = record.record.value
                      val headers = record.record.headers

                      // Decode the wire-level JSON string into DemoEvent
                      // first (ADR 0010's AsyncAPI follow-up, Phase 4 —
                      // kafka-harness's payload is no longer a raw marker
                      // string). A genuine decode failure is itself an
                      // "unprocessable message" case, routed to the DLQ the
                      // same way a recognized-but-bad payload already was.
                      def handle: IO[Unit] = decode[DemoEvent](value) match {
                        case Left(decodeError) =>
                          dlq.guard(topic, key, value)(
                            IO.raiseError(
                              new RuntimeException(
                                s"undecodable message: $decodeError"
                              )
                            )
                          )
                        case Right(event) if event.message == blipMarker =>
                          blippedKeysRef
                            .modify { seen =>
                              if (seen.contains(key)) (seen, false)
                              else (seen + key, true)
                            }
                            .flatMap { firstTime =>
                              if (firstTime)
                                IO.raiseError(
                                  new RuntimeException(
                                    "simulated broker blip - deliberately crashing the consumer stream"
                                  )
                                )
                              else IO.unit
                            }
                        case Right(event) if event.message == poisonMarker =>
                          dlq.guard(topic, key, value)(
                            IO.raiseError(
                              new RuntimeException(
                                s"unprocessable message: ${event.message}"
                              )
                            )
                          )
                        case Right(_) =>
                          dlq.guard(topic, key, value)(IO.unit)
                      }

                      KafkaTracing.traced(tracer)(
                        headers,
                        "consume kafka-harness-demo",
                        topic
                      )(
                        handle *> record.offset.commit
                      )
                    }

                    val consumeLoop: Stream[IO, Unit] =
                      StreamSupervisor.supervise[IO, Unit](
                        StreamSupervisorConfig(
                          baseDelay = 200.millis,
                          maxDelay = 30.seconds
                        )
                      )(logger)(meter) {
                        IO.pure(consumer.stream.evalMap(processRecord))
                      }

                    val lagLoop: Stream[IO, Unit] =
                      ConsumerLag.fromConsumer[IO, String, String](
                        ConsumerLagConfig(
                          pollInterval = 5.seconds,
                          groupId = groupId
                        )
                      )(meter)(consumer)

                    // Registers this harness's one topic/eventType/payload
                    // for AsyncAPI documentation (ADR 0010's AsyncAPI
                    // follow-up, Phase 4) and serves the generated spec
                    // alongside the existing metrics port — kafka-harness
                    // was metrics-port-only before this track.
                    val demoChannel = AsyncApiChannel[DemoEvent](
                      topic = topic,
                      eventType = "DemoEvent"
                    )
                    val docsRoutes =
                      Docs.routes[IO]("kafka-harness", "1.0", List(demoChannel))
                    val boundHttpPort =
                      Port.fromInt(httpPort).getOrElse(port"8080")

                    EmberServerBuilder
                      .default[IO]
                      .withHost(host"0.0.0.0")
                      .withPort(boundHttpPort)
                      .withHttpApp(docsRoutes.orNotFound)
                      .build
                      .use { _ =>
                        List(
                          normalLoop.compile.drain,
                          poisonLoop.compile.drain,
                          blipLoop.compile.drain,
                          burstLoop.compile.drain,
                          consumeLoop.compile.drain,
                          lagLoop.compile.drain
                        ).parSequence_
                      }
                  }
                }
            }
          }
      }
    }
}
