# Plan: TD-20.1 — REST docs convention + coverage check

## Phase 1: Coverage check in purerestlib [checkpoint: d82a744]
- [x] Task: Write failing tests `DocsCoverageSuite`: fully documented fixture (summary,
      description, tag, described path/query/header params, `@description`/`@encodedExample`
      DTO with nested DTO, described 404 error variant) → `Nil`; one bare fixture per gap kind
      → exactly that gap: op summary, op description, param description, non-2xx response
      description, schema property (top-level + nested), body schema example. Plus: ordering
      stable; `Docs.routes` swagger endpoints not reported. 1bf8b08
      (swagger-endpoint test dropped: check takes only the service's endpoints; added an
      array-body test)
- [x] Task: Implement `DocGap` ADT (location + `render`) and
      `Docs.undocumented(title, version, endpoints)`:
      `OpenAPIDocsInterpreter().serverEndpointsToOpenAPI`, walk paths→operations→params/responses
      and `components.schemas` (properties, body-referenced schemas' example). Treat tapir's
      default response descriptions (empty / reason phrase) as missing — verify actual default
      in tapir 1.11.25 first. Scaladoc all public symbols. 1bf8b08
      Deviations: signature is `Docs.undocumented(endpoints: List[AnyEndpoint])` — title/
      version don't affect gaps; services pass `serverEndpoints.map(_.endpoint)`. tapir
      emits `""` for undescribed responses (auto 400 decode-failure is described). Example
      counts if on the class, the body, or every field (recursively) — matches the
      field-level `@encodedExample` style the event case classes already use. tapir moves a
      field's `@description` onto a `$ref`'d component, so a `$ref` property counts as
      described if either side is.
- [x] Task: `sbt scalafmtCheck test` + `coverage test coverageReport` (>80% new code).
      purerestlib 80.4% stmt / 85.2% branch; DocsCoverage 87%, DocGap/Docs 100%. 1bf8b08
- [x] Task: Conductor - User Manual Verification 'Phase 1' (Protocol in workflow.md).
      `scripts/verify-td20-1-phase1.sh`: fmt, test, coverage. d82a744

## Phase 2: README convention [checkpoint: 550c626]
- [x] Task: README "Documenting endpoints" section: convention bullets (from spec), worked
      endpoint + DTO snippet, one-test coverage example
      (`assertEquals(Docs.undocumented(...).map(_.render), Nil)`), owning-service rule, client
      mirrors exempt. Snippet mirrors the test fixture so it stays honest. cc95c85
- [x] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md).
      `scripts/verify-td20-1-phase2.sh`: README section present, snippet lines match the suite fixture. 550c626

## Phase 3: Release v0.7.0 (shipped as v0.7.2) [checkpoint: b7efc39]
- [x] Task: Push main, tag `v0.7.0`, push tag; watch release.yml to green.
      Deviation: v0.7.0 (run 41312137555) published purerestlib/purekafka 0.7.0, then failed
      re-publishing the fixed sbt-purekafka-docs 0.6.1-dev, skipping deploy-docs (the
      v0.4.0 backlog bug; plan wrongly called it moot). Fixed here: 4d9e6ec
      `scripts/publish-unpublished.sh` skips modules whose version is already published.
      v0.7.1 (run 41312948144) failed before publishing anything: parsing `sbt print`
      stdout misaligned versions on CI. 9312fcf: root task `writePublishVersions` writes
      them to a file. v0.7.2 (run 41313547781) green: purerestlib/purekafka 0.7.2 published,
      plugin skipped, deploy-docs ran. Services use 0.7.2 (0.7.0 is the same library code).
- [x] Task: Conductor - User Manual Verification 'Phase 3' (Protocol in workflow.md).
      `scripts/verify-td20-1-phase3.sh`: purerestlib 0.7.2 resolves from GitHub Packages, jar
      contains `purerest/docs/DocGap`; Scaladoc site shows `Docs.undocumented`. b7efc39

## Wrap-up (outside the track)
- gluon TECHNICAL_DEBT.md TD-20.1 ticked, CHANGELOG entry

## Risks
- tapir default response descriptions may be non-empty → gap detection must special-case
- `@encodedExample` may land on schema vs media-type example depending on tapir version →
  check both
- sbt-purekafka-docs republish failure (old backlog item) if its version is fixed — recent
  v0.5–0.6.1 runs green, likely moot
