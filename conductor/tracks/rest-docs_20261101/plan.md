# Plan: TD-20.1 — REST docs convention + coverage check

## Phase 1: Coverage check in purerestlib
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
      `scripts/verify-td20-1-phase1.sh`: fmt, test, coverage.

## Phase 2: README convention
- [ ] Task: README "Documenting endpoints" section: convention bullets (from spec), worked
      endpoint + DTO snippet, one-test coverage example
      (`assertEquals(Docs.undocumented(...).map(_.render), Nil)`), owning-service rule, client
      mirrors exempt. Snippet mirrors the test fixture so it stays honest.
- [ ] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md).

## Phase 3: Release v0.7.0
- [ ] Task: Push main, tag `v0.7.0`, push tag; watch release.yml to green.
- [ ] Task: Conductor - User Manual Verification 'Phase 3' (Protocol in workflow.md).
      `scripts/verify-td20-1-phase3.sh`: purerestlib 0.7.0 resolves from GitHub Packages, jar
      contains `purerest/docs/DocGap`; Scaladoc site shows `Docs.undocumented`.

## Wrap-up (outside the track)
- gluon TECHNICAL_DEBT.md TD-20.1 ticked, CHANGELOG entry

## Risks
- tapir default response descriptions may be non-empty → gap detection must special-case
- `@encodedExample` may land on schema vs media-type example depending on tapir version →
  check both
- sbt-purekafka-docs republish failure (old backlog item) if its version is fixed — recent
  v0.5–0.6.1 runs green, likely moot
