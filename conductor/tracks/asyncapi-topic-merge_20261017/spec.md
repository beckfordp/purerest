# Spec — Fix purekafka AsyncApiDoc same-topic channel merging

## Overview
`AsyncApiDoc.generate` builds its `channels` map via
`ListMap.from(channels.map(c => c.topic -> toChannelItem(c)))` — keyed by
topic alone. Registering two `AsyncApiChannel`s with different
`eventType`s but the same `topic` silently drops all but the last one,
since `ListMap.from` collapses duplicate keys. Found while planning
order-service's `asyncapi-docs_20261017` track: order-service's topics
each multiplex two event types via the envelope pattern
(`inventory-events`: `StockReserved`/`StockReservationFailed`;
`payment-events`: `PaymentSettled`/`PaymentFailed`), which the current
implementation cannot represent. The existing test suite has no
same-topic case (`AsyncApiDocSuite`'s "multiple registered channels" test
uses two distinct topics), so the gap passed unnoticed.

`sttp.apispec.asyncapi`'s own model already supports this: `Message` is a
sealed trait with `SingleMessage` and `OneOfMessage(oneOf:
List[SingleMessage])`; `Operation.message: Option[ReferenceOr[Message]]`
accepts either.

## Functional Requirements
1. Group registered channels by `topic` before building `ChannelItem`s.
2. A topic with exactly one registered channel still produces a plain
   `SingleMessage` on both `publish`/`subscribe` — unchanged behavior for
   existing single-eventType topics (e.g. kafka-harness's `DemoEvent`).
3. A topic with 2+ registered channels produces an AsyncAPI
   `OneOfMessage(oneOf = List[SingleMessage])` on both
   `publish`/`subscribe`, one `SingleMessage` per registered channel
   (same per-message shape as today: `name` = eventType, `payload` =
   that channel's JSON Schema).
4. No change to `AsyncApiChannel`'s own shape (still one
   topic/eventType/schema per instance) — fix is isolated to
   `AsyncApiDoc.generate`'s grouping/building logic.

## Acceptance Criteria
- New test: two channels on the same topic with different eventTypes
  both appear in the generated document (as a `oneOf`), neither silently
  dropped.
- Existing single-channel-per-topic tests still pass unchanged
  (`AsyncApiDocSuite`, `kafka-harness`'s generate/verify scripts).
- Full `purekafka` test suite + `scalafmtCheck` clean.

## Out of Scope
- Any change to `AsyncApiChannel`'s public API.
- order-service's own migration (separate, dependent track:
  `order-service`'s `asyncapi-docs_20261017`).
