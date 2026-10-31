# Spec — AsyncAPI HTML on Node 26

## Overview
`asyncApiGenerate` (sbt-purekafka-docs) and `scripts/generate-kafka-asyncapi-docs.sh` pin `@asyncapi/cli@4.1.1` + `@asyncapi/html-template@1.0.0` and patch the template's `apiVersion` v2→v3, all because the machine ran Node 18 (current CLI needs Node ≥24). gluon is standardising on Node 26 LTS (Active LTS 2026-10-28). Verified in a sandbox on Node 26.13.0: `@asyncapi/cli@6.2.2` + `@asyncapi/html-template@3.5.6`, unpatched, render order-service's real spec with content; installing both once into a cache and rendering from the template's local path takes ~5 s with no network (a registry-named template re-installs every run, ~11 s).

## Functional Requirements
1. **Versions:** `@asyncapi/cli@6.2.2`, `@asyncapi/html-template@3.5.6`, installed together by one `npm install --prefix <cache>`; render from `<cache>/node_modules/@asyncapi/html-template` (local path). Delete the throwaway first render, the patched copy and `patchApiVersion`.
2. **Versioned cache:** cache dir named after both versions (e.g. `asyncapi-cli-tool/cli-6.2.2_html-3.5.6`) so existing 4.1.1 caches in consumers' `target/` are never reused.
3. **Node guard:** before installing/rendering, read `node -v`; below the generator's minimum (24.11) warn once — "AsyncAPI HTML needs Node >= 24.11 (found vX); skipping, spec JSON still generated" — and skip, same warn-don't-fail contract as today. Parsing/comparison is a pure function, unit-tested.
4. **Script** `scripts/generate-kafka-asyncapi-docs.sh`: same versions, cache, guard; long Node-18 diagnosis comments replaced by a short note.
5. **Plugin version** `0.3.0-dev` → `0.4.0-dev` (consumers pin it); `tech-stack.md` updated; released as `v0.4.1` so services can resolve it from GitHub Packages.

## Non-Functional Requirements
- No new runtime dependencies; munit added as a Test dependency of the plugin module only
- Existing warn-and-continue behaviour preserved

## Acceptance Criteria
- Plugin unit tests green; `scripts/verify-kafka-asyncapi-docs.sh` green on Node 26
- A consumer (order-service) on `0.4.0-dev`: `sbt compile` renders `target/asyncapi-docs/index.html` with its channels; on Node 18 it warns and skips
- `v0.4.1` release workflow green

## Out of Scope
- Service repo bumps + CI `setup-node` (follow-up commits in each of the 6 consuming services)
- Backlog: skipping the plugin publish when its fixed version exists (the bump sidesteps it for this release only)
