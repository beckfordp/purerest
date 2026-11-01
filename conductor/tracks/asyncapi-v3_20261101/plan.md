# Plan — AsyncAPI 3.0 send/receive docs

## Phase 1: Document and route
- [x] Task: `AsyncApiDoc` — emit AsyncAPI 3.0 (`send`/`receive` operations, inline channel messages, topic-named keys), munit tests first e6bbb76
- [x] Task: `Docs.routes` — redirect `/docs/asyncapi` → `/docs/asyncapi/`, tests first 11cfbad
- [x] Task: plugin `0.6.0-dev`, build.sbt comment, tech-stack.md cbcc6d8
- [ ] Task: `scripts/verify-asyncapi-v3.sh` — kafka-harness spec validates as 3.0, HTML has send/receive operations, redirect
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Document and route' (Protocol in workflow.md)

## Phase 2: Release
- [ ] Task: tag `v0.6.0`, release workflow green
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Release' (Protocol in workflow.md)
