# Plan — Shared sbt plugin for AsyncAPI doc generation at build time

## Phase 1 — AsyncApiChannelsProvider + AsyncApiSpecWriter (purekafka.docs) [checkpoint: 07e5a59]
- [x] Task: Write failing test: a fixture object implementing `AsyncApiChannelsProvider`, loaded reflectively by its class name, exposes `channels` correctly (Red) `71eaeb4`
- [x] Task: Implement `AsyncApiChannelsProvider` trait (Green) `71eaeb4` — deviation: spec.md's reflection snippet needed a `$`-suffixed class name (Scala's module class vs. its static-forwarder mirror class); see git note on `71eaeb4`
- [x] Task: Write failing test: `AsyncApiSpecWriter.main(Array(providerClass, title, version, outputPath))` writes valid AsyncAPI JSON to `outputPath`, matching `AsyncApiDoc.generate`'s own output for that provider's channels (Red) `8eb7164`
- [x] Task: Implement `AsyncApiSpecWriter` (reflection + file write) (Green) `8eb7164`
- [x] Task: Conductor - User Manual Verification 'Phase 1: AsyncApiChannelsProvider + AsyncApiSpecWriter' (Protocol in workflow.md) `07e5a59`

## Phase 2 — sbt plugin module scaffold + settings [checkpoint: 34f2283]
- [x] Task: Add `modules/sbt-purekafka-docs` to `build.sbt` (Scala 2.12, `sbtPlugin := true`); empty `AutoPlugin` compiles and `sbt publishLocal` succeeds `c307f90`
- [x] Task: Define settings (`asyncApiChannelsClass`, `asyncApiTitle`/`asyncApiVersion` defaulting to `name.value`/`version.value`, `asyncApiOutputDir` defaulting to `target/asyncapi-docs`) `e01d8fb`
- [x] Task: Conductor - User Manual Verification 'Phase 2: Plugin scaffold + settings' (Protocol in workflow.md) `34f2283` — reduced-prompting mode: automated-test verification only, no interactive walkthrough; see git note on `34f2283`

## Phase 3 — asyncApiGenerateSpec task (JSON via runMain)
- [x] Task: Implement `asyncApiGenerateSpec` task — wires `(Compile / runMain).toTask(...)` to `AsyncApiSpecWriter` with the configured settings, writes `target/asyncapi-docs-spec/asyncapi.json` `0b3d0f3` — deviation: combining `.value`-extracted vals into a string passed to `InputKey.toTask(...)` inside a plain task body doesn't compile ("Could not find proxy for val ..."); fixed via `Def.taskDyn` wrapping an inner `Def.task`. Also deviation: same-build `AutoPlugin` discovery doesn't work the normal way (sbt only discovers plugins from the meta-build classpath, not a sibling project's output) — resolved per user's explicit choice: fixed `version := "0.1.0-dev"` on `sbtPureKafkaDocs` (not inheriting `ThisBuild`'s sbt-dynver version), published via `publishLocal`, added to `project/plugins.sbt` as a normal `addSbtPlugin` coordinate; see git note on `0b3d0f3`
- [x] Task: Add the plugin to `kafka-harness` (as the integration fixture — already the reference consumer for `AsyncApiChannel`/`Docs` in the prior track) via a new `DemoChannels` provider object wrapping its existing inline `AsyncApiChannel[DemoEvent]`; run `sbt kafkaHarness/asyncApiGenerateSpec`, confirm the written JSON contains `DemoEvent` `b0fd923` (DemoChannels refactor), `0b3d0f3` (plugin wiring) — confirmed: generated JSON contains the `kafka-harness-demo` channel with `DemoEvent` message/schema
- [~] Task: Conductor - User Manual Verification 'Phase 3: asyncApiGenerateSpec task' (Protocol in workflow.md)

## Phase 4 — asyncApiGenerate task (Node toolchain) + Compile/compile chaining
- [ ] Task: Implement `asyncApiGenerate` task (depends on `asyncApiGenerateSpec`; adapts `generate-kafka-asyncapi-docs.sh`'s `@asyncapi/cli@4.1.1` + patched `@asyncapi/html-template@1.0.0` logic to `scala.sys.process`, same version pins/cache-under-`target/` approach); Node-toolchain failures `[warn]` and continue rather than failing the task
- [ ] Task: Chain `asyncApiGenerate` after `Compile / compile` by default
- [ ] Task: Verify end-to-end on `kafka-harness`: `sbt kafkaHarness/compile` alone (no manual script run) produces `target/asyncapi-docs/index.html` containing `DemoEvent`
- [ ] Task: Conductor - User Manual Verification 'Phase 4: asyncApiGenerate + compile chaining' (Protocol in workflow.md)

## Phase 5 — Full regression
- [ ] Task: Run the full `purerest` test suite + `scalafmtCheck`; confirm no regressions across all modules including the new plugin module
- [ ] Task: Conductor - User Manual Verification 'Phase 5: Full regression' (Protocol in workflow.md)
