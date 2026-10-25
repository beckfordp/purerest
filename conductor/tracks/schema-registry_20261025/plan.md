# Plan — Build-time schema registry check/registration (Rework 5 step 2, ADR 0003)

## Phase 1: SchemaRegistryWriter core logic [checkpoint: PENDING]
- [x] Task: Add `http4s-ember-client` to `purekafka`'s `build.sbt` `fa1c687`
- [x] Task: Write failing tests for `SchemaRegistryWriter`'s
      request-building logic against an in-memory stub `Client[F]`
      (`Client.fromHttpApp`) — proves: correct `artifactId` naming
      (`<topic>-<eventType>`), correct JSON body shape for the
      create-artifact call, the rule-set call fires only after a
      first-time (`version == "1"`) registration, a `409` on the
      rule-set call is swallowed as success, a `400` compatibility
      rejection propagates as a thrown exception (Red) `fa1c687`
- [x] Task: Implement `SchemaRegistryWriter` (`IO[Unit]` internally,
      `main(Array[String])` bridge via `.unsafeRunSync()`), reusing
      `TapirSchemaToJsonSchema` the same way `AsyncApiDoc.toMessage` does
      (Green) `fa1c687` — all 4 tests passed on first implementation
      attempt; 50/50 module-wide, scalafmtCheck clean
- [ ] Task: Conductor - User Manual Verification 'Phase 1:
      SchemaRegistryWriter core logic' (Protocol in workflow.md)

## Phase 2: sbt task wiring + end-to-end verification
- [ ] Task: `PureKafkaDocsPlugin` gains `schemaRegistryUrl`/
      `schemaRegistryGroupId` settings (latter defaults to `name.value`)
      and the `schemaRegistryCheck` task, invoking `SchemaRegistryWriter`
      via the same dedicated-`URLClassLoader`-plus-reflection mechanism
      `asyncApiGenerateSpec` already uses — not chained after
      `Compile/compile`
- [ ] Task: New `scripts/verify-schema-registry-check.sh` (mirroring
      `scripts/verify-kafka-asyncapi-docs.sh`'s own structure), run
      against the real local Apicurio instance already deployed in
      `infra/k8s/local-infra`: first run registers + sets the rule; a
      second identical run is a no-op; a deliberately incompatible
      schema change (a throwaway test fixture) is rejected and fails the
      sbt task
- [ ] Task: Run full test suite + the verify script end-to-end; confirm
      no regressions
- [ ] Task: Conductor - User Manual Verification 'Phase 2: sbt task
      wiring + end-to-end verification' (Protocol in workflow.md)
