# Spec: TD-20.1 — REST docs convention + coverage check

## Overview
Service `/docs` (Swagger via `purerest.docs.Docs`) show bare paths and unexplained schemas.
Write the documentation convention into purerest README and add a pure coverage check to
purerestlib so each service asserts zero gaps in one test. Release purerestlib 0.7.0.
Source: gluon `TECHNICAL_DEBT.md` TD-20.1.

## Functional Requirements
1. **README "Documenting endpoints" section** — convention:
   - every endpoint: `.summary` (one line), `.description` (behaviour, idempotency, notable
     errors), `.tag` per resource;
   - every path/query/header input: `.description`, plus `.example` where value has a format
     (ids, enums, dates);
   - every request/response case class + field: `@description`; each class an
     `@encodedExample`;
   - every error variant: description of when it happens, example body;
   - owning service authoritative; client-side mirror DTOs need none;
   - worked snippet + how to add the coverage test.
2. **`Docs.undocumented(title, version, endpoints): List[DocGap]`** — pure; builds OpenAPI via
   tapir `OpenAPIDocsInterpreter` (same view `/docs` serves) and reports:
   - operation missing summary / description;
   - parameter (path/query/header) missing description;
   - non-2xx response missing a description (tapir defaults / empty count as missing);
   - object schema property (incl. nested component schemas) missing description;
   - object component schema used as request/response body with no example.
3. **`DocGap`** — ADT with readable location (e.g. `GET /orders/{id}: parameter 'id' has no
   description`, `schema OrderResponse.status: no description`); stable ordering for clean
   assert diffs.
4. **Release** purerestlib `v0.7.0` (new public API, early-semver minor) via existing
   tag-triggered pipeline.

## Non-Functional Requirements
- No new runtime deps (tapir-openapi-docs already declared).
- Scaladoc on all new public symbols; scala-fp styleguide.
- `sbt scalafmtCheck test` green.

## Acceptance Criteria
- Fully documented fixture endpoint → `Nil`.
- One test per gap kind: bare fixture reports exactly that gap.
- Swagger's own endpoints never reported.
- README section present; `v0.7.0` published; Scaladoc site updated.

## Out of Scope
- Applying convention to any service / g8 template (TD-20.2–20.10).
- Enforcing param `.example` (format judgment — convention only).
- `.tag` enforcement (convention only).
- gluon `docs/system-design.md` note (TD-20.11).
