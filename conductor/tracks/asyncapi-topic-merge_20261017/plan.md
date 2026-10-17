# Plan — Fix purekafka AsyncApiDoc same-topic channel merging

## Phase 1 — Same-topic merging
- [x] Task: Write failing test in `AsyncApiDocSuite`: two `AsyncApiChannel`s registered on the same topic with different eventTypes both appear in the generated document, each as a distinct message inside a `oneOf` on `publish`/`subscribe` (Red) `b417b2d`
- [x] Task: Implement the fix in `AsyncApiDoc.generate`: group channels by `topic`; a topic with one channel still builds a plain `SingleMessage`-backed `ChannelItem` (unchanged); a topic with 2+ channels builds a `OneOfMessage(oneOf = List[SingleMessage])`-backed `ChannelItem` instead (Green) `b417b2d` — committed Red+Green together
- [x] Task: Run the full `purekafka` test suite + `scalafmtCheck`; confirm no regressions to existing single-channel-per-topic tests or `kafka-harness`'s generate/verify scripts `ba29cf2` — 131 tests passing (33+1 new in `pureKafka`, 45/23/29 in the other 3 modules), `scalafmtCheck` clean after reformatting the fix; `./scripts/verify-kafka-asyncapi-docs.sh` passes end-to-end against kafka-harness's single-channel `DemoEvent` case
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Same-topic merging' (Protocol in workflow.md)
