# Plan: Review archived scripts and recover/rename or delete each based on genuine ongoing use

## Phase 1: Audit
- [ ] Task: Read each of the 19 scripts in `scripts/archive/` and classify it keep/delete against the agreed criterion (verifies behavior `sbt test` doesn't cover, and that behavior still exists in the current codebase). Record the classification + one-line reasoning for each in this track's plan.md.
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Audit' (Protocol in workflow.md).

## Phase 2: Recover keepers
- [ ] Task: For each script classified "keep": move it to `scripts/`, fix any stale references (module names, ports, paths — some predate the purerestlib rename) so it runs against current code, and run it once to confirm it passes.
- [ ] Task: Add a one-line README mention (command + what it checks) for each recovered script, matching `verify-observability-stack.sh`'s existing documentation.
- [ ] Task: Fix README's existing `./scripts/archive/run-services.sh` reference (line 163) and "lightweight path" callout (lines 67-70) to point at wherever it ends up.
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Recover keepers' (Protocol in workflow.md).

## Phase 3: Delete obsolete scripts + close out docs
- [ ] Task: Delete every script classified "delete" (`scripts/archive/` should end up empty).
- [ ] Task: Update `conductor/product.md`'s Iteration 2 goal 3 ("Reduce operational noise") to reflect the resolved decision.
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Delete obsolete scripts + close out docs' (Protocol in workflow.md).
