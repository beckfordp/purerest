# Plan — Shared sbt plugin for AsyncAPI doc generation at build time

## Phase 1 — AsyncApiChannelsProvider + AsyncApiSpecWriter (purekafka.docs)
- [x] Task: Write failing test: a fixture object implementing `AsyncApiChannelsProvider`, loaded reflectively by its class name, exposes `channels` correctly (Red) `71eaeb4`
- [x] Task: Implement `AsyncApiChannelsProvider` trait (Green) `71eaeb4` — deviation: spec.md's reflection snippet needed a `$`-suffixed class name (Scala's module class vs. its static-forwarder mirror class); see git note on `71eaeb4`
- [ ] Task: Write failing test: `AsyncApiSpecWriter.main(Array(providerClass, title, version, outputPath))` writes valid AsyncAPI JSON to `outputPath`, matching `AsyncApiDoc.generate`'s own output for that provider's channels (Red)
- [ ] Task: Implement `AsyncApiSpecWriter` (reflection + file write) (Green)
- [ ] Task: Conductor - User Manual Verification 'Phase 1: AsyncApiChannelsProvider + AsyncApiSpecWriter' (Protocol in workflow.md)

## Phase 2 — sbt plugin module scaffold + settings
- [ ] Task: Add `modules/sbt-purekafka-docs` to `build.sbt` (Scala 2.12, `sbtPlugin := true`); empty `AutoPlugin` compiles and `sbt publishLocal` succeeds
- [ ] Task: Define settings (`asyncApiChannelsClass`, `asyncApiTitle`/`asyncApiVersion` defaulting to `name.value`/`version.value`, `asyncApiOutputDir` defaulting to `target/asyncapi-docs`)
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Plugin scaffold + settings' (Protocol in workflow.md)

## Phase 3 — asyncApiGenerateSpec task (JSON via runMain)
- [ ] Task: Implement `asyncApiGenerateSpec` task — wires `(Compile / runMain).toTask(...)` to `AsyncApiSpecWriter` with the configured settings, writes `target/asyncapi-docs-spec/asyncapi.json`
- [ ] Task: Add the plugin to `kafka-harness` (as the integration fixture — already the reference consumer for `AsyncApiChannel`/`Docs` in the prior track) via a new `DemoChannels` provider object wrapping its existing inline `AsyncApiChannel[DemoEvent]`; run `sbt kafkaHarness/asyncApiGenerateSpec`, confirm the written JSON contains `DemoEvent`
- [ ] Task: Conductor - User Manual Verification 'Phase 3: asyncApiGenerateSpec task' (Protocol in workflow.md)

## Phase 4 — asyncApiGenerate task (Node toolchain) + Compile/compile chaining
- [ ] Task: Implement `asyncApiGenerate` task (depends on `asyncApiGenerateSpec`; adapts `generate-kafka-asyncapi-docs.sh`'s `@asyncapi/cli@4.1.1` + patched `@asyncapi/html-template@1.0.0` logic to `scala.sys.process`, same version pins/cache-under-`target/` approach); Node-toolchain failures `[warn]` and continue rather than failing the task
- [ ] Task: Chain `asyncApiGenerate` after `Compile / compile` by default
- [ ] Task: Verify end-to-end on `kafka-harness`: `sbt kafkaHarness/compile` alone (no manual script run) produces `target/asyncapi-docs/index.html` containing `DemoEvent`
- [ ] Task: Conductor - User Manual Verification 'Phase 4: asyncApiGenerate + compile chaining' (Protocol in workflow.md)

## Phase 5 — Full regression
- [ ] Task: Run the full `purerest` test suite + `scalafmtCheck`; confirm no regressions across all modules including the new plugin module
- [ ] Task: Conductor - User Manual Verification 'Phase 5: Full regression' (Protocol in workflow.md)
