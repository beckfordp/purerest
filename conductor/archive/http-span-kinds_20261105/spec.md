# Spec: HTTP span kinds + client spans (gluon PERF-5.10)

## Overview
A checkout trace holds only order-service spans: services' outgoing http4s
clients aren't wrapped, and `ClientTracing` only injects headers (no span).
HTTP server spans are kind INTERNAL.

## Functional Requirements
1. `ClientTracing.middleware`: with an active span, each request runs in a
   `Client`-kind child span named `<METHOD> <host>`, attributes
   `http.request.method`, `server.address`, `url.path`,
   `http.response.status_code`; 5xx / raised error → span status Error. The
   injected `traceparent` is that client span's. No active span → passthrough.
2. `ServerTracing.middleware`: span kind `Server`; attributes
   `http.request.method`, `url.path`, `http.response.status_code`; 5xx → Error.

## Acceptance Criteria
- Suites assert kinds, parenting, attributes; `sbt scalafmtCheck test` green.
- Released as v0.14.0 (local `publishLocal`).

## Out of Scope
- Route-template span names (raw path stays; ServerMetrics relies on it).
