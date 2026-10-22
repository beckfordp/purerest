# Plan — Document CDC-published Kafka events with real publish/subscribe direction

## Phase 1 — PublisherKind/ChannelDirection ADTs + AsyncApiChannel's direction field [checkpoint: 33a694d]
- [x] Task: Write failing tests: `AsyncApiChannel[A]` defaults `direction` to `ChannelDirection.Both(PublisherKind.Direct)` when not specified; can also be constructed with an explicit `direction` (e.g. `Subscribe`, `Publish(Cdc)`) (Red) `618f619`
- [x] Task: Implement `PublisherKind`/`ChannelDirection` ADTs and add the `direction` field to `AsyncApiChannel[A]` (Green) `618f619`
- [x] Task: Conductor - User Manual Verification 'Phase 1: PublisherKind/ChannelDirection ADTs' (Protocol in workflow.md) `33a694d` — reduced-prompting mode: automated-test verification only, no interactive walkthrough; see git note on `33a694d`

## Phase 2 — Direction-aware document generation [checkpoint: 6d93ba2]
- [x] Task: Write failing tests: a `Subscribe`-direction channel produces no `publish` operation for its topic; a `Publish`-direction channel produces no `subscribe` operation; a topic combining a `Subscribe`-only channel and a separate `Publish` channel (different event types) splits correctly into the right oneOf buckets (Red) `00426a0`
- [x] Task: Rework `AsyncApiDoc.toChannelItem`/`generate` to filter publish/subscribe oneOf membership by each channel's `direction`, omitting an operation entirely (not an empty oneOf) when no registered channel on that topic qualifies for it (Green) `00426a0` — deviation: Phase 3's `withCdcNote`/description wiring was implemented together with this phase's filtering (same call sites); see git note on `00426a0`
- [x] Task: Conductor - User Manual Verification 'Phase 2: Direction-aware document generation' (Protocol in workflow.md) `6d93ba2` — reduced-prompting mode: automated-test verification only, no interactive walkthrough; see git note on `6d93ba2`

## Phase 3 — CDC description label [checkpoint: 6e3a3c5]
- [x] Task: Write failing tests: a `Publish(Cdc)` channel's message includes a description noting CDC/outbox delivery; a `Publish(Direct)` channel's message has no such description (Red) `69bb374` — deviation: passed immediately (Green, not Red) since the implementation was already done in Phase 2's combined commit; see git note on `69bb374`
- [x] Task: Implement description population in `AsyncApiDoc.toMessage` based on `PublisherKind` (Green) `00426a0` — done as part of Phase 2's rework (same call sites); see git note on `00426a0`
- [x] Task: Conductor - User Manual Verification 'Phase 3: CDC description label' (Protocol in workflow.md) `6e3a3c5` — reduced-prompting mode: automated-test verification only, no interactive walkthrough; see git note on `6e3a3c5`

## Phase 4 — Backward-compat regression + kafka-harness demo channel [checkpoint: 07ea4e0]
- [x] Task: Run the full existing `purekafka` test suite (`AsyncApiChannelSuite`, `AsyncApiDocSuite`, `DocsSuite`, `AsyncApiChannelsProviderSuite`, `AsyncApiSpecWriterSuite`) unchanged — confirm all pass, proving the new `direction` field's default preserves today's output exactly — 44/44 pass; `DocsSuite`/`AsyncApiChannelsProviderSuite`/`AsyncApiSpecWriterSuite` confirmed byte-identical since track start (no behavioral diff, only earlier scalafmt-drift cleanup touched `DocsSuite`)
- [x] Task: Add a new, explicitly-commented-as-illustrative `Publish(Cdc)` channel to `kafka-harness`'s `DemoChannels` (kafka-harness has no real outbox — documented as a demo-only stand-in), leaving the existing `kafka-harness-demo` channel's `Both(Direct)` behavior unchanged `017972a`
- [x] Task: Verify via `sbt kafkaHarness/compile`: rendered HTML shows the new channel as publish-only with its CDC description note, and the existing channel's output is byte-for-byte unchanged `017972a` — confirmed: `kafka-harness-outbox-demo` has publish only (no subscribe) with the CDC/Debezium description; `kafka-harness-demo` unchanged (both operations, no description); rendered HTML contains `DemoEvent`, `OrderStatusChanged`, `CDC`, `Debezium`; see git note on `017972a`
- [x] Task: Conductor - User Manual Verification 'Phase 4: Backward-compat regression + kafka-harness demo channel' (Protocol in workflow.md) `07ea4e0` — reduced-prompting mode: automated-test verification only, no interactive walkthrough; see git note on `07ea4e0`

## Phase: Review Fixes
- [x] Task: Apply review suggestions `eddd33b` — fixed the Low finding (`AsyncApiDoc.toChannelItem` derived each `Both`-direction channel's JSON Schema twice, once per operation); reworked to derive each channel's message once and reuse it across publish/subscribe (stripping the CDC note via `.copy` when the subscribe side needs to differ). Verified: 44/44 `pureKafka` tests pass unchanged, `kafkaHarness/compile` output confirmed byte-identical (same publish/subscribe presence, same CDC description), full repo `compile test scalafmtCheckAll scalafmtSbtCheck` clean.
