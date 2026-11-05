# Spec: Milestones without correlation_id (gluon PERF-5.6)

## Overview
gluon decided (2026-11-05) that logs correlate by `trace_id`/`span_id`
(`Logging.traceCorrelated`) and `order_id`; `correlation_id` equals `order_id`
and no caller ever passes one.

## Functional Requirements
1. `Milestones.log(logger)(milestone, orderId, extra)`: the `correlationId`
   parameter is removed; the line carries `milestone`, `order_id` and `extra`.

## Acceptance Criteria
- `MilestonesSuite` updated; `sbt scalafmtCheck test` green.
- Released as v0.13.0 (breaking signature).

## Out of Scope
- The Kafka envelope's `correlationId` and `correlation_id` header (contract).
