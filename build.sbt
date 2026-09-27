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
val testcontainersScalaVersion = "0.43.6"
val catsRetryVersion = "4.0.0"
val resilience4jVersion = "2.3.0"
val logstashLogbackEncoderVersion = "9.0"

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
    publishTo := Some("GitHub Packages" at "https://maven.pkg.github.com/beckfordp/purerest"),
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
  .enablePlugins(GatlingPlugin)
  .settings(
    scalaVersion := scala3Version,
    name := "load-test",
    libraryDependencies ++= Seq(
      "io.gatling.highcharts" % "gatling-charts-highcharts" % "3.15.1" % Test,
      "io.gatling" % "gatling-test-framework" % "3.15.1" % Test
    )
  )

// root: aggregates the modules so `sbt compile`/`sbt test` run across all of
// them; not published itself.
lazy val root = project
  .in(file("."))
  .aggregate(purerestlib, orderService, inventoryService)
  .settings(
    name := "purerest",
    publish / skip := true
  )
