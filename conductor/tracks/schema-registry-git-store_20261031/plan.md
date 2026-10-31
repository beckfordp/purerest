# Plan — Schema registry: versionable schemas, producer-only registration

## Phase 1: Writer and plugin [checkpoint: 752c8c1]
- [x] Task: `RegistrySchema` — draft-07 conversion + closed content model, munit tests first fca8856
- [x] Task: `SchemaRegistryWriter` — producer label, BACKWARD_TRANSITIVE, Subscribe dry-run (404 warn/skip, 4xx fail), tests first e8fb6ba
- [x] Task: plugin `schemaRegistryProducer` setting, version 0.5.0-dev, tech-stack.md 2fbc36d
- [x] Task: `scripts/verify-schema-registry-evolution.sh` against local Apicurio 130b212
- [x] Task: Conductor - User Manual Verification 'Phase 1: Writer and plugin' (Protocol in workflow.md)

## Phase 2: Release [checkpoint: a5ddfd5]
- [x] Task: tag `v0.5.0`, release workflow green (run 41244847166)
- [x] Task: Conductor - User Manual Verification 'Phase 2: Release' (Protocol in workflow.md)
