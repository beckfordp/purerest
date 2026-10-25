# Spec — Build-time schema registry check/registration (Rework 5 step 2, ADR 0003)

## Overview
Adds the build-time compatibility-enforcement mechanism ADR 0003 settled
on: a new `purekafka.docs.SchemaRegistryWriter` that derives each
registered `AsyncApiChannel`'s JSON Schema (reusing
`TapirSchemaToJsonSchema`, the exact same call `AsyncApiDoc.scala`
already makes) and registers/checks it against Apicurio Registry via its
REST API — empirically confirmed against the real local instance deployed
in Rework 5 step 1. A new sbt task in the existing `PureKafkaDocsPlugin`
invokes it, mirroring `asyncApiGenerateSpec`'s own reflective-invocation
pattern exactly (dedicated `URLClassLoader`, not `(Compile/runMain)`).

## Functional Requirements
- **`SchemaRegistryWriter.main(providerClass, registryUrl, groupId)`**
  (mirrors `AsyncApiSpecWriter`'s signature/reflection style): builds its
  logic as `IO[Unit]` using `http4s-ember-client` (`EmberClientBuilder`) +
  `http4s-circe`, bridged to the required synchronous
  `main(Array[String])` entry point via one `.unsafeRunSync()` call —
  consistent with `purekafka`'s existing cats-effect/http4s dependencies
  (already used by `StreamSupervisor`/`DeadLetter`/`Resilience`), not
  `java.net.http`. Loads the given `AsyncApiChannelsProvider` by class
  name, and for each `AsyncApiChannel[A]`:
  1. Derives its JSON Schema via
     `TapirSchemaToJsonSchema(channel.schema, markOptionsAsNullable =
     true)`.
  2. `artifactId = s"${channel.topic}-${channel.eventType}"`.
  3. `POST {registryUrl}/apis/registry/v3/groups/{groupId}/artifacts?ifExists=FIND_OR_CREATE_VERSION`
     with the schema as the content — confirmed idempotent (identical
     content returns the existing version, no duplicate) and confirmed to
     reject (`400`) an incompatible change once a rule exists.
  4. On that artifact's *first* registration only (response has
     `version.version == "1"`), also `POST .../rules` with
     `{"ruleType":"COMPATIBILITY","config":"BACKWARD"}` — a `409
     RuleAlreadyExistsException` is treated as success, not an error
     (confirmed live: re-POSTing an existing rule 409s).
  5. No retry/circuit-breaker wrapping on the registry call for this
     first version — left unguarded, deliberately deferred.
- **New `http4s-ember-client` dependency** added to `purekafka`'s
  `build.sbt` (it currently only has `http4s-dsl`/`http4s-circe`, both
  server-side).
- **New sbt task `schemaRegistryCheck`** in `PureKafkaDocsPlugin`,
  reusing the existing `asyncApiChannelsClass` setting (no duplicate
  setting) plus two new ones: `schemaRegistryUrl: SettingKey[String]` and
  `schemaRegistryGroupId: SettingKey[String]` (defaults to `name.value`,
  i.e. one group per service). Invoked via the same
  dedicated-`URLClassLoader`-plus-reflection mechanism
  `asyncApiGenerateSpec` already uses.
- **Not auto-chained** after `Compile/compile` — a separate,
  explicitly-invoked task (`sbt schemaRegistryCheck`), run in CI and
  optionally by a developer locally. Keeps local `sbt compile`/`sbt test`
  working even when the registry isn't running.
- A failing compatibility check must fail the sbt task (non-zero exit /
  thrown exception), not just log a warning — this is the actual
  enforcement mechanism.

## Non-Functional Requirements
- `http4s-ember-client` is the only new dependency, and it's already
  present transitively on every consuming service's own classpath (via
  `purerestlib`), so nothing new gets loaded into the plugin's isolated
  `URLClassLoader`.
- This track only builds the mechanism in `purerest` — wiring it into any
  of the 4 services is out of scope, a separate conductor track per
  service (Rework 5 step 3).

## Acceptance Criteria
- Unit tests prove `SchemaRegistryWriter`'s request-building logic
  against an in-memory stub `Client[F]` (`Client.fromHttpApp`, matching
  this codebase's dominant test style) — no real registry needed for
  these.
- An integration-level test (or a documented manual verification script,
  mirroring `scripts/verify-kafka-asyncapi-docs.sh`'s own precedent)
  proves end-to-end against the real local Apicurio instance: first
  registration succeeds and sets the rule; an identical second run is a
  no-op; an incompatible schema change is rejected with a clear error;
  the sbt task fails loudly on that rejection.
- `sbt schemaRegistryCheck` runs successfully against the already-deployed
  local Apicurio instance when pointed at it.

## Out of Scope
- Per-service adoption (Rework 5 step 3) and the `pure-service-generator`
  retrofit (step 4) — separate tracks.
- Auto-chaining after compile, and retry/circuit-breaker wrapping on the
  registry call — both decided against above, deferred.
- Any registry-side configuration beyond the one `BACKWARD` rule per
  artifact (global registry settings, auth, multi-environment registry
  URLs) — not needed for local dev, not decided here.
