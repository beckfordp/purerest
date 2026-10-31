# Plan — AsyncAPI HTML on Node 26

## Phase 1: Generator on current versions
- [x] Task: pure Node-version guard (`NodeVersion.parse`/`supports`) with munit tests first 382cecc
- [~] Task: `AsyncApiHtmlGenerator` — 6.2.2 + 3.5.6, one install into a versioned cache, render from local template path, guard; drop the patch
- [ ] Task: `generate-kafka-asyncapi-docs.sh` — same versions, cache, guard
- [ ] Task: plugin version 0.4.0-dev, tech-stack.md
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Generator on current versions' (Protocol in workflow.md)

## Phase 2: Release
- [ ] Task: tag `v0.4.1`, release workflow green, `sbt-purekafka-docs:0.4.0-dev` resolvable
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Release' (Protocol in workflow.md)
