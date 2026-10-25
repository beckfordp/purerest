# Plan — Build-time schema registry check/registration (Rework 5 step 2, ADR 0003)

## Phase 1: SchemaRegistryWriter core logic [checkpoint: 8d6cd6e]
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
- [x] Task: Conductor - User Manual Verification 'Phase 1:
      SchemaRegistryWriter core logic' (Protocol in workflow.md)
      `8d6cd6e` — reduced-prompting mode: automated-test verification
      only (50 passed), no interactive walkthrough; see git note on
      `8d6cd6e`

## Phase 2: sbt task wiring + end-to-end verification [checkpoint: TBD]
- [x] Task: `PureKafkaDocsPlugin` gains `schemaRegistryUrl`/
      `schemaRegistryGroupId` settings (latter defaults to `name.value`)
      and the `schemaRegistryCheck` task, invoking `SchemaRegistryWriter`
      via the same dedicated-`URLClassLoader`-plus-reflection mechanism
      `asyncApiGenerateSpec` already uses — not chained after
      `Compile/compile` `031f4de`
- [x] Task: New `scripts/verify-schema-registry-check.sh` `031f4de` —
      design pivot found while implementing: sbt AutoPlugins are only
      discoverable from a build's own meta-build classpath
      (`project/plugins.sbt`), not a sibling project's output in the
      *same* build (confirmed against `sbtPureKafkaDocs`'s own existing
      version-pinning comment in `build.sbt`), so `kafka-harness` can't
      just `enablePlugins` it directly. The script instead
      `publishLocal`s `purekafka` + `sbt-purekafka-docs` and drives a
      throwaway sbt project (built by the script itself, torn down on
      exit) that consumes them the same way a real external consumer
      eventually will — not a production change to kafka-harness.
      Bumped `sbtPureKafkaDocs`'s pinned dev version 0.1.0-dev ->
      0.2.0-dev per its own comment's instruction to do so when the
      plugin changes within a track. Live-verified against the real
      local Apicurio instance: first run registers + sets the BACKWARD
      rule (200/204); second identical run is a no-op (200, no second
      rule-set call needed); a type-changing (String -> Int) payload
      edit is rejected and fails the task — empirically, Apicurio 3.3.2
      returns a `500 SchemaException: could not determine version` for
      this particular kind of incompatible change rather than the `400
      RuleViolationException` seen during Rework 5 step 1's infra
      testing for other kinds of changes; `SchemaRegistryWriter`
      already treats any non-2xx uniformly as a failure, so both shapes
      are handled correctly either way. Also found and fixed:
      `schemaRegistryCheck` referenced `(Compile / classDirectory)` /
      `(Compile / dependencyClasspath)` without ever depending on
      `(Compile / compile)` itself — since, unlike `asyncApiGenerateSpec`,
      it's deliberately *not* reached via the `Compile/compile` taskDyn
      chain, running it standalone threw a bare `ClassNotFoundException`
      for the never-compiled channels-provider class until `(Compile /
      compile).value` was added as its first line.
- [x] Task: Run full test suite + the verify script end-to-end; confirm
      no regressions `031f4de` — `scalafmtCheckAll; test`: clean, exit 0,
      0 failed across every suite; `verify-schema-registry-check.sh`:
      all checks passed against the real local Apicurio instance
- [ ] Task: Conductor - User Manual Verification 'Phase 2: sbt task
      wiring + end-to-end verification' (Protocol in workflow.md)
