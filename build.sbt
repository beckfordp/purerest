// Scala 3 version used to compile this project.
val scala3Version = "3.9.0"

// Shared dependency versions, referenced by modules as they adopt these
// libraries in later tracks.
val catsEffectVersion = "3.7.0"
val http4sVersion = "0.23.37"
val circeVersion = "0.14.16"
val munitVersion = "1.3.6"
val munitCatsEffectVersion = "2.2.1"
val otel4sVersion = "1.1.0"
val openTelemetryVersion = "1.66.0"
val log4catsVersion = "2.8.0"
val tapirVersion = "1.11.25"
val skunkVersion = "1.0.0"
val flywayVersion = "11.8.2"
val postgresqlJdbcVersion = "42.7.13"
val pureconfigVersion = "0.17.10"
// Bumped 0.43.6 -> 0.44.1 (testcontainers-java 1.21.1 -> 2.0.3, a major
// version jump) specifically to fix Testcontainers-Kafka startup against
// this machine's OrbStack/Docker 29.8.2: 1.21.1's shaded docker-java client
// hardcodes Docker API version 1.32 and ignores DOCKER_API_VERSION/
// DOCKER_HOST overrides, which a modern Docker Engine (minimum supported
// API 1.40) rejects outright. Confirmed Postgres-based suites still pass
// after this bump (see purekafka_20261015 track's Phase 5 work).
val testcontainersScalaVersion = "0.44.1"
val catsRetryVersion = "4.0.0"
val resilience4jVersion = "2.3.0"
val logstashLogbackEncoderVersion = "9.0"
// fs2-kafka: pure-FP, FS2-native Kafka client — same version/groupId gluon's
// own generated services pin (e.g. services/order-service/build.sbt), for
// ecosystem consistency.
val fs2KafkaVersion = "3.6.0"

ThisBuild / evictionErrorLevel := Level.Error

// Skunk 1.0.0 depends on otel4s-core/-core-common/-core-metrics 0.16.0 (its own
// optional tracing integration), which conflicts with this build's pinned otel4s
// 1.1.0 (an early-semver 0.x -> 1.x jump, which evictionErrorLevel := Level.Error
// now fails the build on rather than only warning). "Highest version wins" is the
// correct resolution here — nothing in this project invokes Skunk's otel4s
// integration — so these three coordinates are pinned explicitly rather than left
// to eviction. See conductor/tech-stack.md's "Transitive version drift" deferred
// concern.
ThisBuild / dependencyOverrides ++= Seq(
  "org.typelevel" %% "otel4s-core" % otel4sVersion,
  "org.typelevel" %% "otel4s-core-common" % otel4sVersion,
  "org.typelevel" %% "otel4s-core-metrics" % otel4sVersion
)

// Settings shared by every module in this build.
lazy val commonSettings = Seq(
  scalaVersion := scala3Version,

  libraryDependencies ++= Seq(
    // munit: test framework used across this project (Scala-native, no JUnit dependency).
    "org.scalameta" %% "munit" % munitVersion % Test,
    // munit-cats-effect: lets test bodies return IO[Unit] and run under munit directly.
    "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test
  )
)

// purerestlib: the platform library providing cross-cutting microservice
// concerns (tracing, observability, resilience) as composable, annotation-free
// building blocks. order-service and inventory-service both depend on it. Named
// "purerestlib" (not "purerest") so the repo/root-project name "purerest" is
// free at the top level — this module's own Scala packages are still purerest.*.
lazy val purerestlib = project
  .in(file("modules/purerestlib"))
  .settings(commonSettings)
  .settings(
    name := "purerestlib",
    // Real Maven/Ivy coordinate for publishing purerestlib as a jar, derived from
    // this project's own GitHub remote (github.com/beckfordp/purerest).
    organization := "io.github.beckfordp",
    // early-semver: purerest's own published version communicates binary
    // compatibility the way its 0.x/1.x/etc. Typelevel-ecosystem dependencies
    // already do, so a future consumer's eviction checks (once purerest is
    // published as a real jar) have real semver metadata to reason about instead
    // of guessing. See conductor/tech-stack.md's "Transitive version drift" note.
    versionScheme := Some("early-semver"),
    // Publishes to this repo's GitHub Packages Maven registry on a tag push (see
    // .github/workflows/release.yml). Credentials come from env vars the workflow
    // sets from its built-in GITHUB_TOKEN — unset locally, so `publishLocal` (which
    // ignores publishTo/credentials entirely) is unaffected.
    publishTo := Some(
      "GitHub Packages" at "https://maven.pkg.github.com/beckfordp/purerest"
    ),
    credentials += Credentials(
      "GitHub Package Registry",
      "maven.pkg.github.com",
      sys.env.getOrElse("GITHUB_ACTOR", ""),
      sys.env.getOrElse("GITHUB_TOKEN", "")
    ),
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.http4s" %% "http4s-ember-client" % http4sVersion,
      "org.http4s" %% "http4s-circe" % http4sVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      // otel4s (oteljava backend): tracing API + a real OpenTelemetry Java SDK
      // underneath, giving us real exporters (console, and in-memory for tests).
      "org.typelevel" %% "otel4s-oteljava" % otel4sVersion,
      // Console/logging span exporter, for manual verification when running a
      // service locally.
      "io.opentelemetry" % "opentelemetry-exporter-logging" % openTelemetryVersion,
      // In-memory span exporter/testkit. Deliberately a normal compile dependency
      // (not Test-scoped): purerest.tracing.Tracing.test is a public testing helper
      // consumed by order-service's and inventory-service's own test suites, not
      // just purerest's — Test-scope deps don't propagate to consuming modules.
      "org.typelevel" %% "otel4s-oteljava-testkit" % otel4sVersion,
      // Only used to stand up a stub server in purerest's own tests — purerest's
      // main code has no server dependency.
      "org.http4s" %% "http4s-ember-server" % http4sVersion % Test,
      // Only used to build routes in purerest's own middleware tests.
      "org.http4s" %% "http4s-dsl" % http4sVersion % Test,
      // log4cats: structured, tagless-final logging.
      "org.typelevel" %% "log4cats-core" % log4catsVersion,
      "org.typelevel" %% "log4cats-slf4j" % log4catsVersion,
      // SLF4J binding — without one, log lines are silently dropped (NOP logger).
      // Logback (not slf4j-simple) because its pattern layout can render MDC
      // values (%X{trace_id}/%X{span_id}) — log4cats-slf4j pushes our per-call
      // context Map into SLF4J's MDC around each log statement, and slf4j-simple's
      // fixed layout has no way to display it. Runtime-only: never referenced
      // directly in code; configured via logback.xml.
      "ch.qos.logback" % "logback-classic" % "1.6.3" % Runtime,
      // logstash-logback-encoder: JSON encoder for logback-docker.xml, used only
      // inside the observability-stack Docker images (see logback-docker.xml) —
      // trace_id/span_id/structured context become real Kibana-searchable
      // fields, unlike logback.xml's plain-text pattern layout used for local
      // sbt bgRun. Runtime-only: never referenced directly in code.
      "net.logstash.logback" % "logstash-logback-encoder" % logstashLogbackEncoderVersion % Runtime,
      // In-memory capturing logger, for asserting on log output in tests.
      "org.typelevel" %% "log4cats-testing" % log4catsVersion % Test,
      // tapir: endpoints described once as Endpoint/ServerEndpoint values,
      // interpreted into both real http4s routes and generated OpenAPI/Swagger
      // docs from the same source of truth (purerest.docs).
      "com.softwaremill.sttp.tapir" %% "tapir-core" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-openapi-docs" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % tapirVersion,
      // cats-retry: composable retry policies (exponential backoff, jitter, max
      // attempts) for purerest's resilient HTTP client.
      "com.github.cb372" %% "cats-retry" % catsRetryVersion,
      // resilience4j-circuitbreaker: mature circuit-breaker state machine (core,
      // non-reactive module only), wrapped internally as a pure combinator — never
      // exposed in purerest's public API.
      "io.github.resilience4j" % "resilience4j-circuitbreaker" % resilience4jVersion,
      // Prometheus scrape-endpoint exporter for otel4s/OTel SDK metrics — still an
      // incubating OTel component, hence the "-alpha" qualifier tracking the main
      // opentelemetry-java release train.
      "io.opentelemetry" % "opentelemetry-exporter-prometheus" % s"$openTelemetryVersion-alpha"
    )
  )

// purekafka: Kafka resilience + observability building blocks — generic
// F[A] call resilience, consumer stream supervision, dead-letter routing,
// and tracing/metrics/logs across the Kafka boundary (ADR 0010 in gluon).
// Sibling to purerestlib, not folded into it: services that don't touch
// Kafka (catalog-service, cart-service) shouldn't pull in fs2-kafka
// transitively. Deliberately has no dependency on purerestlib itself —
// reuses the same cats-retry/resilience4j-core/otel4s/log4cats
// dependencies, not purerestlib's own http4s-coupled resilience code.
lazy val pureKafka = project
  .in(file("modules/purekafka"))
  .settings(commonSettings)
  .settings(
    name := "purekafka",
    // Real Maven/Ivy coordinate for publishing purekafka as a jar, derived
    // from this project's own GitHub remote (github.com/beckfordp/purerest).
    organization := "io.github.beckfordp",
    // early-semver: same reasoning as purerestlib's own versionScheme note
    // above — communicates binary compatibility once this is a real
    // published jar, instead of a future consumer having to guess.
    versionScheme := Some("early-semver"),
    // Publishes to this repo's GitHub Packages Maven registry on a tag push
    // (see .github/workflows/release.yml). Credentials come from env vars
    // the workflow sets from its built-in GITHUB_TOKEN — unset locally, so
    // `publishLocal` (which ignores publishTo/credentials entirely) is
    // unaffected.
    publishTo := Some(
      "GitHub Packages" at "https://maven.pkg.github.com/beckfordp/purerest"
    ),
    credentials += Credentials(
      "GitHub Package Registry",
      "maven.pkg.github.com",
      sys.env.getOrElse("GITHUB_ACTOR", ""),
      sys.env.getOrElse("GITHUB_TOKEN", "")
    ),
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "com.github.fd4s" %% "fs2-kafka" % fs2KafkaVersion,
      // otel4s (oteljava backend): same tracing/metrics API purerestlib uses
      // for HTTP, now across the Kafka boundary.
      "org.typelevel" %% "otel4s-oteljava" % otel4sVersion,
      // In-memory metrics/span testkit — compile-scoped (not Test), same
      // reasoning as purerestlib's own otel4s-oteljava-testkit dependency:
      // a public testing helper other modules' test suites may need too,
      // and Test-scope deps don't propagate to consuming modules.
      "org.typelevel" %% "otel4s-oteljava-testkit" % otel4sVersion,
      // log4cats: structured, tagless-final logging, same MDC convention
      // purerestlib uses for HTTP (trace_id/span_id), extended here to
      // topic/partition/offset/eventType/correlationId.
      "org.typelevel" %% "log4cats-core" % log4catsVersion,
      "org.typelevel" %% "log4cats-slf4j" % log4catsVersion,
      // In-memory capturing logger, for asserting on log output in tests.
      "org.typelevel" %% "log4cats-testing" % log4catsVersion % Test,
      // cats-retry: composable retry policies, same engine purerestlib's
      // HTTP Retry middleware uses — here wrapping a generic F[A] directly
      // instead of delegating to http4s's own Retry client middleware (not
      // applicable to a non-HTTP call).
      "com.github.cb372" %% "cats-retry" % catsRetryVersion,
      // resilience4j-circuitbreaker: same mature circuit-breaker state
      // machine (core, non-reactive module only) purerestlib's HTTP
      // CircuitBreaker wraps, reused here for a generic F[A].
      "io.github.resilience4j" % "resilience4j-circuitbreaker" % resilience4jVersion,
      // tapir-core: just the Schema[A] derivation, for purekafka.docs'
      // AsyncApiChannel registration primitive (ADR 0010's AsyncAPI
      // follow-up) — reuses the same JSON-Schema-shaped derivation
      // purerestlib's REST docs already use, not a second schema story.
      // Not tapir-http4s-server/-swagger-ui-bundle yet — those land in a
      // later phase of this same track, once there's an actual route to
      // serve.
      "com.softwaremill.sttp.tapir" %% "tapir-core" % tapirVersion,
      // tapir-apispec-docs: brings `TapirSchemaToJsonSchema` — a
      // standalone tapir Schema[A] -> sttp.apispec.Schema (JSON Schema)
      // converter, factored out of tapir-openapi-docs specifically because
      // it's format-agnostic (OpenAPI and AsyncAPI both build on the same
      // sttp.apispec.Schema). Deliberate deviation from this track's own
      // spec.md, which assumed "no good library fit exists" for AsyncAPI
      // generation and planned to hand-roll circe Json directly — found
      // while implementing Phase 2 that tapir-apispec-docs already
      // transitively depends on asyncapi-model (see its own published POM),
      // meaning the sttp/tapir ecosystem already ships a real, typed
      // AsyncAPI 2.6.0 document model + circe codecs (asyncapi-circe,
      // pinned to the exact same 0.11.9 tapir-apispec-docs itself uses, to
      // avoid a version mismatch) - using those instead of hand-rolling is
      // strictly better: type-safe document construction instead of
      // manually-assembled JSON, and no new JSON-shape bugs to maintain.
      "com.softwaremill.sttp.tapir" %% "tapir-apispec-docs" % tapirVersion,
      "com.softwaremill.sttp.apispec" %% "asyncapi-circe" % "0.11.9",
      // http4s-dsl/-circe: just enough to serve the AsyncApiDoc.generate
      // output as a plain HttpRoutes[F] (Phase 3 of the AsyncAPI track) —
      // not tapir-http4s-server/-swagger-ui-bundle, since this isn't a
      // tapir endpoint being documented, just a static generated document
      // being served at a fixed route.
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-circe" % http4sVersion,
      // http4s-ember-client: SchemaRegistryWriter's Apicurio REST calls
      // (Rework 5 step 2, ADR 0003) - consistent with this module's
      // existing cats-effect/http4s dependencies (StreamSupervisor/
      // DeadLetter/Resilience), not java.net.http. Already present
      // transitively on every consuming service's own classpath via
      // purerestlib, so nothing new gets loaded into the sbt plugin's
      // isolated URLClassLoader.
      "org.http4s" %% "http4s-ember-client" % http4sVersion
    )
  )

// kafka-harness: a small, continuously-running fixture app demonstrating
// purekafka's combinators under real, sustained Kafka traffic with a real
// Prometheus exporter (unlike purekafka's own Testcontainers-based
// KafkaResilienceLoadTest, which uses otel4s's in-memory testkit and tears
// down after one assertion run) - so purekafka's metrics become visible in
// Grafana locally, the same way order-service/inventory-service already are
// for the HTTP story. Docker-packaged and added to docker-compose's
// "observability" profile alongside them; see scripts/verify-kafka-observability.sh.
// Depends on purerestlib (not just pureKafka) specifically to reuse
// purerest.metrics.Metrics.oteljava and purerest.tracing.Tracing.console —
// the real exporters order-service/inventory-service's own Main.scala
// already build this exact way — rather than hand-rolling a second copy of
// that OtelJava/SdkMeterProvider/PrometheusHttpServer wiring here. This app
// is a standalone demo, not something other Kafka-touching services
// depend on, so it's exempt from pureKafka's own "no purerestlib
// dependency" rule (that rule is about pureKafka-the-library staying
// decoupled, not about every future consumer).
lazy val kafkaHarness = project
  .in(file("modules/kafka-harness"))
  .dependsOn(purerestlib, pureKafka)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(commonSettings)
  .settings(
    name := "kafka-harness",
    Docker / packageName := "kafka-harness",
    dockerBaseImage := "eclipse-temurin:21-jre",
    dockerUpdateLatest := true,
    // Docker tags can't contain '+', but sbt-dynver's untagged-history format
    // does (e.g. "0.0.0+300-2be9b9c0+...") — sanitize before it becomes an
    // (invalid) image tag. Same as order-service/inventory-service.
    Docker / version := version.value.replace("+", "-"),
    // 8080: the new AsyncAPI-docs HTTP server (ADR 0010's AsyncAPI
    // follow-up, Phase 4) - not present before this track, kafka-harness
    // was metrics-port-only (9093) until now.
    dockerExposedPorts := Seq(8080, 9093),
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "com.github.fd4s" %% "fs2-kafka" % fs2KafkaVersion,
      // circe: encodes/decodes DemoEvent as the Kafka message value (JSON
      // string over the wire, same hand-rolled-codec convention every
      // gluon service already uses for its own payloads - no shared
      // typed-Kafka-codec library).
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      // http4s-ember-server: the new AsyncAPI-docs HTTP server mounting
      // purekafka.docs.Docs.routes - kafka-harness was metrics-port-only
      // (via purerest.metrics.Metrics.oteljava's own internal Prometheus
      // server) before this track.
      "org.http4s" %% "http4s-ember-server" % http4sVersion
    )
  )

// sbt-purekafka-docs: a real sbt plugin (not a library) wrapping
// purekafka.docs' AsyncApiSpecWriter so any consuming service can
// generate/serve its AsyncAPI docs via sbt tasks instead of a shell script
// (ADR 0010's AsyncAPI follow-up, this track). Scala 2.12 (sbt's own plugin
// Scala version) — necessarily NOT commonSettings (Scala 3) and NOT in
// root's .aggregate(...) below, same reasoning as loadTest further down: a
// different Scala binary version can't be aggregated alongside the Scala 3
// modules without breaking the normal compile/test loop.
lazy val sbtPureKafkaDocs = project
  .in(file("modules/sbt-purekafka-docs"))
  .settings(
    name := "sbt-purekafka-docs",
    organization := "io.github.beckfordp",
    scalaVersion := "2.12.20",
    sbtPlugin := true,
    // Native-packager is an optional collaborator: the plugin appends the
    // generated HTML to Universal/mappings when a consuming project also
    // enables JavaAppPackaging/UniversalPlugin (TD-6). A plugin-to-plugin
    // dependency, so the Universal configuration/mappings keys resolve.
    libraryDependencies += Defaults.sbtPluginExtra(
      "com.github.sbt" % "sbt-native-packager" % "1.11.1",
      (pluginCrossBuild / sbtBinaryVersion).value,
      (pluginCrossBuild / scalaBinaryVersion).value
    ),
    // Fixed, not sbt-dynver's per-commit ThisBuild version: AutoPlugins are
    // only discoverable from a consuming build's own meta-build classpath, so
    // external consumers pin a normal addSbtPlugin coordinate. Bump this by
    // hand (and re-run publishLocal) whenever the plugin itself changes.
    version := "0.3.0-dev",
    // Same GitHub Packages registry/credentials as pureKafka above - this is
    // its first real publish (release.yml now runs sbtPureKafkaDocs/publish
    // alongside it).
    publishTo := Some(
      "GitHub Packages" at "https://maven.pkg.github.com/beckfordp/purerest"
    ),
    credentials += Credentials(
      "GitHub Package Registry",
      "maven.pkg.github.com",
      sys.env.getOrElse("GITHUB_ACTOR", ""),
      sys.env.getOrElse("GITHUB_TOKEN", "")
    )
  )

// order-service: REST API that places orders, calling inventory-service via
// purerest's client to reserve stock. Depends on inventoryService in Test
// scope only, to run a real inventory-service in-process for integration
// tests — main code has no dependency on inventory-service.
lazy val orderService = project
  .in(file("modules/order-service"))
  .dependsOn(purerestlib, inventoryService % Test)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(commonSettings)
  .settings(
    name := "order-service",
    // Local-only image for the observability stack (docker-compose's
    // "observability" profile) — see conductor/tech-stack.md.
    Docker / packageName := "order-service",
    dockerBaseImage := "eclipse-temurin:21-jre",
    dockerUpdateLatest := true,
    // Docker tags can't contain '+', but sbt-dynver's untagged-history format
    // does (e.g. "0.0.0+300-2be9b9c0+...") — sanitize before it becomes an
    // (invalid) image tag.
    Docker / version := version.value.replace("+", "-"),
    dockerExposedPorts := Seq(8080, 9090),
    // JSON logging (see logback-docker.xml) inside the container only — local
    // `sbt bgRun` is unaffected, since this is Docker-format-scoped.
    Universal / javaOptions += "-Dlogback.configurationFile=logback-docker.xml",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-circe" % http4sVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-core" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % tapirVersion,
      // skunk: non-blocking, pure-FP Postgres access — order-service's persistence layer.
      "org.tpolecat" %% "skunk-core" % skunkVersion,
      // flyway: JDBC-based schema migration tool, run on startup to create/update the
      // orders table. Independent of Skunk (which handles all runtime queries).
      "org.flywaydb" % "flyway-core" % flywayVersion,
      "org.flywaydb" % "flyway-database-postgresql" % flywayVersion,
      // postgresql (pgjdbc): build-only JDBC driver, used solely by Flyway to run
      // migrations. Runtime-only: never referenced directly in code, loaded by
      // Flyway/JDBC's DriverManager via SPI.
      "org.postgresql" % "postgresql" % postgresqlJdbcVersion % Runtime,
      // pureconfig: loads application.conf (Postgres connection, service port,
      // inventory base URL) into typed config case classes.
      "com.github.pureconfig" %% "pureconfig-core" % pureconfigVersion,
      // testcontainers-scala: spins up a real, ephemeral Postgres container for
      // integration tests (not used by main code).
      "com.dimafeng" %% "testcontainers-scala-postgresql" % testcontainersScalaVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-munit" % testcontainersScalaVersion % Test,
      // log4cats-testing: purerest keeps this Test-scoped (doesn't propagate via
      // .dependsOn), so order-service declares its own copy to assert on log
      // output (StructuredTestingLogger) in its own tests.
      "org.typelevel" %% "log4cats-testing" % log4catsVersion % Test
    )
  )

// inventory-service: REST API exposing stock reservation endpoints; a second,
// independent consumer of purerest.
lazy val inventoryService = project
  .in(file("modules/inventory-service"))
  .dependsOn(purerestlib)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(commonSettings)
  .settings(
    name := "inventory-service",
    // Local-only image for the observability stack (docker-compose's
    // "observability" profile) — see conductor/tech-stack.md.
    Docker / packageName := "inventory-service",
    dockerBaseImage := "eclipse-temurin:21-jre",
    dockerUpdateLatest := true,
    // Docker tags can't contain '+', but sbt-dynver's untagged-history format
    // does (e.g. "0.0.0+300-2be9b9c0+...") — sanitize before it becomes an
    // (invalid) image tag.
    Docker / version := version.value.replace("+", "-"),
    dockerExposedPorts := Seq(8081, 9091),
    // JSON logging (see logback-docker.xml) inside the container only — local
    // `sbt bgRun` is unaffected, since this is Docker-format-scoped.
    Universal / javaOptions += "-Dlogback.configurationFile=logback-docker.xml",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-circe" % http4sVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-core" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % tapirVersion,
      // pureconfig: loads application.conf (port, metrics port, service name,
      // induced-failure defaults) into typed config case classes.
      "com.github.pureconfig" %% "pureconfig-core" % pureconfigVersion,
      // log4cats-testing: see order-service's identical comment above.
      "org.typelevel" %% "log4cats-testing" % log4catsVersion % Test
    )
  )

// load-test: Gatling simulations exercising order-service's POST /orders call
// path under sustained load, to generate realistic RED + resilience metrics.
// Deliberately NOT added to root's .aggregate(...) below — Gatling's own
// convention runs simulations under a dedicated `Gatling` sbt configuration
// (`sbt loadTest/Gatling/test`), not the default `test` task, but its
// heavier dependencies (an embedded Netty/Jetty-based HTTP stack, chart
// generation) would still get pulled onto the classpath by a plain `sbt test`
// if aggregated — keeping it out of .aggregate keeps the normal fast dev/test
// loop untouched; reach it explicitly via `sbt loadTest/...` or
// scripts/loadtest-purerest.sh.
lazy val loadTest = project
  .in(file("modules/load-test"))
  .dependsOn(pureKafka)
  .enablePlugins(GatlingPlugin)
  .settings(
    scalaVersion := scala3Version,
    name := "load-test",
    libraryDependencies ++= Seq(
      "io.gatling.highcharts" % "gatling-charts-highcharts" % "3.15.1" % Test,
      "io.gatling" % "gatling-test-framework" % "3.15.1" % Test,
      // purekafka's own sustained-load harness (KafkaResilienceLoadTest) is a
      // plain munit suite, not a Gatling simulation - checked Maven Central
      // directly: io.gatling ships no Kafka protocol module at all (http,
      // jms, mqtt, grpc, jdbc, redis, graphql, but no kafka); the only
      // third-party options (org.galaxio/ru.tinkoff/com.github.mnogu) are
      // Scala 2.x-only, and even cross-version-shimmed in, their DSL only
      // drives raw Kafka send/receive - not purekafka's own wrapped
      // combinators, which is the actual point of this load test. See
      // KafkaResilienceLoadTest's own doc comment for the full reasoning.
      "org.scalameta" %% "munit" % munitVersion % Test,
      "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test,
      // testcontainers-scala-kafka: a real, ephemeral Kafka broker for this
      // sustained-load run - same "test the logic against a real broker, not
      // a fake" convention this project's Postgres integration tests already
      // use via testcontainers-scala-postgresql.
      "com.dimafeng" %% "testcontainers-scala-kafka" % testcontainersScalaVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-munit" % testcontainersScalaVersion % Test
    )
  )

// root: aggregates the modules so `sbt compile`/`sbt test` run across all of
// them; not published itself.
lazy val root = project
  .in(file("."))
  .aggregate(
    purerestlib,
    pureKafka,
    orderService,
    inventoryService,
    kafkaHarness
  )
  .settings(
    name := "purerest",
    publish / skip := true
  )
