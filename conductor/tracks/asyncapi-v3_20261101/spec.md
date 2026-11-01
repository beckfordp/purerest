# Spec — AsyncAPI 3.0 send/receive docs (gluon TD-19.1/19.2)

## Overview
`AsyncApiDoc` emits AsyncAPI 2.6.0 and maps a service's produced events to `publish`, which 2.x defines from the client's side (clients publish, the service receives). Every service's `/docs/asyncapi/` therefore says it receives what it produces. Decision (gluon TECHNICAL_DEBT.md TD-19, 2026-11-01): move to AsyncAPI 3.0, whose `send`/`receive` actions are from the service's own side. Spike: a hand-converted order-service spec validates with cli 6.2.2 and renders `RECEIVE payment-events` / `SEND order-events` with html-template 3.5.6. sttp-apispec has no 3.0 model (0.11.9 and latest 0.12.1 are 2.6.0 only), so the document is built as circe `Json`. Separately, `/docs/asyncapi` (no trailing slash) serves the HTML but its relative assets 404.

## Functional Requirements
1. **3.0 document:** `asyncapi: "3.0.0"`; one channel per topic (`address` = topic) with its messages inline, keyed by `eventType`; payload from the channel's tapir `Schema` via `TapirSchemaToJsonSchema` (unchanged); `name`/`title` = `eventType`.
2. **Operations:** produced (`Publish`/`Both`) → `action: send`; consumed (`Subscribe`/`Both`) → `action: receive`; each operation references its channel and its messages (`#/channels/<key>/messages/<eventType>`). An operation is omitted when no channel on the topic qualifies.
3. **Keys:** operation key = topic name; only a topic with both a send and a receive operation gets `<topic>-send` / `<topic>-receive`. Channel/operation keys are sanitised to the 3.0 key pattern `[A-Za-z0-9_-]` (other chars → `_`).
4. **CDC note:** a message whose channel's publisher is `Cdc` carries the CDC description. In 3.0 a message is defined once per channel, so a `Both(Cdc)` message carries it on both operations (it is a true property of the message).
5. **Redirect:** `Docs.routes` answers `GET /docs/asyncapi` with `301` → `/docs/asyncapi/`.
6. **Plugin:** `sbt-purekafka-docs` → `0.6.0-dev`; released as `v0.6.0`.

## Non-Functional Requirements
- No new dependencies; output has no `null` values

## Acceptance Criteria
- Unit tests green; `scripts/verify-asyncapi-v3.sh` green: kafka-harness's served spec is `3.0.0` and passes `asyncapi validate`, the rendered HTML has send and receive operations, `/docs/asyncapi` redirects
- `v0.6.0` release workflow green

## Out of Scope
- Service bumps and consumer schema descriptions (gluon TD-19.3/19.4); generator (TD-19.5)
- The template's hardcoded "Accepts one of the following messages" text under `SEND`
