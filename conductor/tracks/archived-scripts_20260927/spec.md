# Spec: Review archived scripts and recover/rename or delete each based on genuine ongoing use

## Overview
`scripts/archive/` holds 19 scripts archived wholesale by a prior track, pending this
decision (see `product.md`'s Iteration 2 goal 3, "Reduce operational noise"). This track
reviews each against the agreed criterion — keep if it verifies real end-to-end behavior
(a live docker-compose stack, an external system, a full workflow) that `sbt test`
doesn't already cover — and either recovers it to `scripts/` or deletes it. One concrete
finding already surfaced: README.md still tells users to run
`./scripts/archive/run-services.sh` for day-to-day dev (line 163) and calls it part of the
"lightweight path" used "day-to-day" (lines 67-70) — a strong signal it belongs in
`scripts/`, not archived.

## Functional Requirements
1. Audit each of the 19 scripts in `scripts/archive/` against: does it verify behavior
   `sbt test` doesn't already cover, and does that behavior still exist in the current
   codebase (not describing a removed/renamed feature from a since-closed track)?
2. For each script judged worth keeping: move it to `scripts/`, fix any stale references
   (module names, ports, paths — some predate the purerestlib rename) so it runs
   correctly against the current codebase, and run it once to confirm it passes.
3. Add a one-line README mention (command + what it checks) for each recovered script,
   matching how `verify-observability-stack.sh` is documented today.
4. Fix README's existing `./scripts/archive/run-services.sh` reference and "lightweight
   path" callout to point at wherever it ends up.
5. Delete every script judged obsolete.
6. Update `product.md`'s Iteration 2 goal 3 to reflect the resolved decision.

## Non-Functional Requirements
- No rewriting of a script's actual assertions/behavior beyond the minimal fixes needed
  to make it runnable again — this is a disposition decision, not a rewrite.
- Every recovered script is manually run once against the current codebase before being
  kept, to confirm it still passes.

## Acceptance Criteria
- Every one of the 19 scripts is either present in `scripts/` (recovered, confirmed
  passing) or deleted (not present anywhere) — `scripts/archive/` ends up empty.
- README.md accurately reflects the final location of every script it mentions.
- `product.md`'s Iteration 2 goal 3 reflects the resolved decision.

## Out of Scope
- Rewriting or meaningfully improving any script's logic/assertions.
- `conductor/archive/` (completed track records) — unrelated, not touched.
- Writing any new scripts.
