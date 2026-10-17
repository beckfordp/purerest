# Plan — Fix purekafka AsyncApiDoc same-topic channel merging

## Phase 1 — Same-topic merging
- [x] Task: Write failing test in `AsyncApiDocSuite`: two `AsyncApiChannel`s registered on the same topic with different eventTypes both appear in the generated document, each as a distinct message inside a `oneOf` on `publish`/`subscribe` (Red) `b417b2d`
- [x] Task: Implement the fix in `AsyncApiDoc.generate`: group channels by `topic`; a topic with one channel still builds a plain `SingleMessage`-backed `ChannelItem` (unchanged); a topic with 2+ channels builds a `OneOfMessage(oneOf = List[SingleMessage])`-backed `ChannelItem` instead (Green) `b417b2d` — committed Red+Green together
- [~] Task: Run the full `purekafka` test suite + `scalafmtCheck`; confirm no regressions to existing single-channel-per-topic tests or `kafka-harness`'s generate/verify scripts
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Same-topic merging' (Protocol in workflow.md)
