# Spec — Document CDC-published Kafka events with real publish/subscribe direction

## Overview
`AsyncApiChannel[A]` currently has no direction concept at all —
`AsyncApiDoc.generate` conservatively emits both a `publish` and a
`subscribe` operation for every registered channel, regardless of which
side the registering service actually is. This is visibly wrong for
services like order-service that only *consume* certain event types
(their docs currently show a `publish` operation for events they never
publish). It also has no way to say a channel is published via Debezium
CDC/outbox (ADR 0008 in gluon) rather than a direct producer call —
information worth surfacing since CDC-published topics have no
application-level publish call for `purekafka`'s resilience/observability
to wrap (per ADR 0008's own "Interaction with ADR 0010" section).

This track adds real direction (publish-only / subscribe-only / both) to
`AsyncApiChannel`, and a CDC-vs-direct label on the publish side,
rendered as a description note on the affected message. The outbox
payload itself needs no new schema modeling — ADR 0008 confirmed the
outbox row's payload column already holds the same inner domain-event
envelope `AsyncApiChannel[A]` already registers today (e.g.
`AsyncApiChannel[OrderReservedEvent]`); only the *provenance* label is
new.

## Functional Requirements
1. New `PublisherKind` ADT (`Direct | Cdc`) and `ChannelDirection` ADT
   combining both axes so `publisher` is only expressible where it's
   meaningful: `ChannelDirection.Publish(publisher: PublisherKind) |
   ChannelDirection.Subscribe | ChannelDirection.Both(publisher:
   PublisherKind)`.
2. `AsyncApiChannel[A]` gains a `direction: ChannelDirection` field,
   defaulting to `ChannelDirection.Both(PublisherKind.Direct)` —
   preserves today's behavior (both operations, no CDC note) for every
   existing call site with zero code changes required.
3. `AsyncApiDoc.generate`/`toChannelItem` reworked to respect direction:
   a topic's `publish` oneOf only includes messages from
   Publish/Both-direction channels; `subscribe` only includes
   Subscribe/Both channels. A `ChannelItem` omits the `publish` (or
   `subscribe`) operation entirely if no registered channel on that
   topic qualifies — not an empty oneOf.
4. Each message gets a `description` populated only when its channel's
   publisher is `Cdc` (e.g. "Published via Debezium CDC outbox (ADR
   0008) — no direct producer call."), attached where that message
   appears in the publish-direction oneOf. Direct-published channels get
   no such note.
5. Update kafka-harness's `DemoChannels`: its existing
   `kafka-harness-demo` channel keeps `Both(Direct)` (matches its real
   produce+consume behavior, zero change to its own generated output);
   add a new, explicitly-commented-as-illustrative second channel
   demonstrating `Publish(Cdc)` (kafka-harness has no real outbox —
   documented as a demo-only stand-in).

## Non-Functional Requirements
- Fully backward compatible: no existing `AsyncApiChannel` registration
  (in purerest, kafka-harness, or any already-migrated external consumer
  like order-service) needs to change to keep compiling and producing
  identical output to today.
- No new runtime dependencies.

## Acceptance Criteria
- A `Subscribe`-direction channel produces no `publish` operation for its
  topic (assuming no other channel on that topic is publish-eligible).
- A `Publish(Cdc)`-direction channel produces a `publish` operation only,
  with a message description noting CDC/outbox delivery.
- A topic combining a `Subscribe`-only channel and a separate
  `Publish(Cdc)` channel (different event types, same topic) correctly
  splits: the publish oneOf contains only the CDC-published event type,
  the subscribe oneOf contains only the subscribe-only event type.
- All pre-existing tests (`AsyncApiChannelSuite`, `AsyncApiDocSuite`,
  `DocsSuite`, `AsyncApiChannelsProviderSuite`, `AsyncApiSpecWriterSuite`,
  kafka-harness's own build) continue passing unchanged, proving
  backward compatibility.
- `sbt kafkaHarness/compile`'s rendered HTML visibly shows the new
  CDC-labeled demo channel as publish-only with its description note.

## Out of Scope
- Migrating order-service (or any other gluon service) to use the new
  `direction`/`publisher` fields — a separate follow-up track in that
  service's own repo, once this ships and `purekafka` is republished.
- Any change to Debezium/Kafka Connect infrastructure itself — this
  track only concerns `purekafka`'s AsyncAPI *documentation* generation,
  not CDC delivery mechanics.
- AsyncAPI vendor extension fields (`x-publisher-kind`) —
  description-text only, per the rendering decision made during this
  track's spec discussion.
