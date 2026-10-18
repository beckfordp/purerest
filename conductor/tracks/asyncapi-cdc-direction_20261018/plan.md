# Plan — Document CDC-published Kafka events with real publish/subscribe direction

## Phase 1 — PublisherKind/ChannelDirection ADTs + AsyncApiChannel's direction field [checkpoint: 33a694d]
- [x] Task: Write failing tests: `AsyncApiChannel[A]` defaults `direction` to `ChannelDirection.Both(PublisherKind.Direct)` when not specified; can also be constructed with an explicit `direction` (e.g. `Subscribe`, `Publish(Cdc)`) (Red) `618f619`
- [x] Task: Implement `PublisherKind`/`ChannelDirection` ADTs and add the `direction` field to `AsyncApiChannel[A]` (Green) `618f619`
- [x] Task: Conductor - User Manual Verification 'Phase 1: PublisherKind/ChannelDirection ADTs' (Protocol in workflow.md) `33a694d` — reduced-prompting mode: automated-test verification only, no interactive walkthrough; see git note on `33a694d`

## Phase 2 — Direction-aware document generation
- [x] Task: Write failing tests: a `Subscribe`-direction channel produces no `publish` operation for its topic; a `Publish`-direction channel produces no `subscribe` operation; a topic combining a `Subscribe`-only channel and a separate `Publish` channel (different event types) splits correctly into the right oneOf buckets (Red) `00426a0`
- [x] Task: Rework `AsyncApiDoc.toChannelItem`/`generate` to filter publish/subscribe oneOf membership by each channel's `direction`, omitting an operation entirely (not an empty oneOf) when no registered channel on that topic qualifies for it (Green) `00426a0` — deviation: Phase 3's `withCdcNote`/description wiring was implemented together with this phase's filtering (same call sites); see git note on `00426a0`
- [~] Task: Conductor - User Manual Verification 'Phase 2: Direction-aware document generation' (Protocol in workflow.md)

## Phase 3 — CDC description label
- [ ] Task: Write failing tests: a `Publish(Cdc)` channel's message includes a description noting CDC/outbox delivery; a `Publish(Direct)` channel's message has no such description (Red)
- [ ] Task: Implement description population in `AsyncApiDoc.toMessage` based on `PublisherKind` (Green)
- [ ] Task: Conductor - User Manual Verification 'Phase 3: CDC description label' (Protocol in workflow.md)

## Phase 4 — Backward-compat regression + kafka-harness demo channel
- [ ] Task: Run the full existing `purekafka` test suite (`AsyncApiChannelSuite`, `AsyncApiDocSuite`, `DocsSuite`, `AsyncApiChannelsProviderSuite`, `AsyncApiSpecWriterSuite`) unchanged — confirm all pass, proving the new `direction` field's default preserves today's output exactly
- [ ] Task: Add a new, explicitly-commented-as-illustrative `Publish(Cdc)` channel to `kafka-harness`'s `DemoChannels` (kafka-harness has no real outbox — documented as a demo-only stand-in), leaving the existing `kafka-harness-demo` channel's `Both(Direct)` behavior unchanged
- [ ] Task: Verify via `sbt kafkaHarness/compile`: rendered HTML shows the new channel as publish-only with its CDC description note, and the existing channel's output is byte-for-byte unchanged
- [ ] Task: Conductor - User Manual Verification 'Phase 4: Backward-compat regression + kafka-harness demo channel' (Protocol in workflow.md)
