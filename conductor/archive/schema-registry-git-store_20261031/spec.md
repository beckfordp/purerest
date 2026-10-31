# Spec — Schema registry: versionable schemas, producer-only registration (gluon TD-18.1/18.2)

## Overview
gluon TD-18 makes git the schema registry's persistent store (ADR 0003, 2026-10-31 note). Two purekafka prerequisites: schemas must be versionable (TD-11: draft 2020-12 makes Apicurio's checker 500 on any change) and only producers may write an artifact (TD-14: consumer mirrors overwrite producer artifacts). Probed live against Apicurio 3.3.2 on 2026-10-31: draft-07 versions without error; with an open content model even an optional field addition is "narrowed" (rejected), with `additionalProperties: false` it registers as version 2; `versions?dryRun=true` checks without persisting (404 when artifact absent); artifact `labels` round-trip.

## Functional Requirements
1. **Draft-07:** registered schemas carry `"$schema": "http://json-schema.org/draft-07/schema#"`; `$defs` → `definitions`, `#/$defs/` refs → `#/definitions/`. Pure function, unit-tested.
2. **Closed producer schemas:** every object schema with `properties` and no `additionalProperties` gets `additionalProperties: false`, so adding an optional field is BACKWARD-compatible.
3. **Producer-only registration:** `Publish`/`Both` channels register (FIND_OR_CREATE_VERSION) with label `producer=<service>`; new artifacts get rule `COMPATIBILITY=BACKWARD_TRANSITIVE`.
4. **Consumer dry-run:** `Subscribe` channels dry-run their (open, draft-07) mirror against the producer's artifact: compatible → ok, nothing written; 404 → warn and skip; incompatible → fail the task.
5. **Plugin:** new setting `schemaRegistryProducer` (default `name.value`) passed to the writer; plugin `0.5.0-dev`; released as `v0.5.0`.

## Non-Functional Requirements
- No new dependencies; `register` stays a pure `F[_]` core testable with a stub client

## Acceptance Criteria
- Unit tests green; `scripts/verify-schema-registry-evolution.sh` green against local Apicurio: v1, optional add → v2, breaking change fails, label + rule set, consumer dry-run (absent/compatible/incompatible)
- `v0.5.0` release workflow green

## Out of Scope
- Export/restore and CI baseline (gluon TD-18.3–18.5); service bumps
