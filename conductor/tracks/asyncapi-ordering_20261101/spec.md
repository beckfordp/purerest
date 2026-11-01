# Spec — AsyncAPI docs: sends first

## Overview
html-template lists operations in document order, which today follows each service's channel registration order, so order-service's docs open with `RECEIVE payment-events`. A service's own events should come first.

## Functional Requirements
1. **Operations:** every `send` operation before every `receive` operation.
2. **Within each group:** registration order (authors list events in lifecycle order), not alphabetical.
3. **Channels:** same order as operations — topics with a `send` first, then receive-only topics, each in registration order.
4. **Plugin:** `sbt-purekafka-docs` → `0.6.1-dev` (release re-publishes the plugin; an existing version fails); released as `v0.6.1`.

## Acceptance Criteria
- Unit tests green; `scripts/verify-asyncapi-v3.sh` green; `v0.6.1` release green

## Out of Scope
- Service/generator bumps (their own tracks)
