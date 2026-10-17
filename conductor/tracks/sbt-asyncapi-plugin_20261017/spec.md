# Spec — Shared sbt plugin for AsyncAPI doc generation at build time

## Overview
Replaces each consuming service's own copy of
`generate-kafka-asyncapi-docs.sh`/`verify-kafka-asyncapi-docs.sh` with a
real sbt plugin, published from `purerest` the same way
`purekafka`/`purerestlib` are. Two parts:

1. **`purekafka.docs` (existing Scala 3 module)** gains a tiny
   `AsyncApiChannelsProvider` trait (`def channels:
   List[AsyncApiChannel[?]]`) and a new `AsyncApiSpecWriter` object with
   a `main(args: Array[String])` entry point: given a provider class
   name + title + version + output path, it reflectively loads the named
   object (`Class.forName(...).getField("MODULE$").get(null)`), casts to
   the trait, calls `AsyncApiDoc.generate(title, version,
   provider.channels)`, and writes the JSON to disk. Runs entirely in the
   *consuming* project's own JVM/classpath via that project's own
   `Compile / runMain` — no HTTP server, no new purekafka dependency for
   the plugin itself (it's already on the classpath since the service
   already depends on purekafka to build `AsyncApiChannel` values).
2. **New `modules/sbt-purekafka-docs` (new Scala 2.12 module,
   `sbtPlugin := true`)**: an sbt `AutoPlugin` providing:
   - `asyncApiChannelsClass: SettingKey[String]` — required, fully-
     qualified provider object name.
   - `asyncApiTitle`/`asyncApiVersion: SettingKey[String]` — default to
     `name.value`/`version.value`.
   - `asyncApiOutputDir: SettingKey[File]` — default
     `target/asyncapi-docs`.
   - `asyncApiGenerateSpec: TaskKey[File]` — runs step 1 above via
     `(Compile / runMain).toTask(...)`, writes
     `target/asyncapi-docs-spec/asyncapi.json`. Pure Scala, no Node —
     fails loudly on error (wrong class name, trait not implemented,
     etc.).
   - `asyncApiGenerate: TaskKey[File]` — depends on
     `asyncApiGenerateSpec`; shells out to the same `@asyncapi/cli@4.1.1`
     + patched `@asyncapi/html-template@1.0.0` pipeline the shell
     scripts already use (same version pins, same npm-install-once-
     cache-under-`target/` approach, same apiVersion-v3 patch), via
     `scala.sys.process`. On Node-toolchain failure: logs a `[warn]` and
     returns the (now-stale-or-missing) output path rather than failing
     the task.
   - Auto-chains `asyncApiGenerate` after `Compile / compile` by default
     (every `sbt compile`/`test`/`run` regenerates docs) — both tasks
     remain independently runnable too (`sbt asyncApiGenerate`).

## Functional Requirements
1. `AsyncApiChannelsProvider` trait + `AsyncApiSpecWriter` added to
   `purekafka.docs` (existing module), with tests.
2. New `sbt-purekafka-docs` plugin module (Scala 2.12, `sbtPlugin :=
   true`), published via `sbt publishLocal` like `purekafka`/
   `purerestlib`.
3. Plugin settings/tasks as above; `Compile / compile` chains
   `asyncApiGenerate` afterward.
4. Node-toolchain errors warn-and-continue; spec-write errors (step 1)
   fail the task loudly.
5. Out of scope for *this* track: wiring `order-service` to actually use
   the plugin (tracked separately, after this publishes).

## Acceptance Criteria
- `AsyncApiSpecWriter`'s reflection + JSON-write works against a real
  provider object in a test fixture.
- The plugin, added to `kafka-harness` (the existing reference consumer
  for `AsyncApiChannel`/`Docs`), makes a plain `sbt kafkaHarness/compile`
  produce `target/asyncapi-docs/index.html` containing `DemoEvent` — no
  manual script run.
- Full `purekafka` test suite + `scalafmtCheck` still passes; new plugin
  module compiles under Scala 2.12.

## Out of Scope
- Wiring `order-service` (or any other service) to consume this plugin —
  separate follow-up step, per PLAN.md's own wording for this item.
- Removing the existing shell scripts from `purerest`/`order-service` —
  can happen once the plugin is proven working end-to-end on a real
  service; not blocking this track.
