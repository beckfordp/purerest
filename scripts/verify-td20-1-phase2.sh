#!/usr/bin/env bash
set -euo pipefail

# Phase 2 verification for rest-docs_20261101 (gluon TECHNICAL_DEBT.md TD-20.1):
# README has the "Documenting endpoints" section, and its endpoint snippet still
# matches the fully documented fixture in DocsCoverageSuite.
#
# Usage: ./scripts/verify-td20-1-phase2.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
SUITE=modules/purerestlib/src/test/scala/purerest/docs/DocsCoverageSuite.scala

grep -q '^## Documenting endpoints$' README.md || {
  echo "FAIL: README has no 'Documenting endpoints' section" >&2
  exit 1
}
echo "  PASS README section present"

for line in \
  '"orders" / path[String]("id").description("Order id.").example("ord-1")' \
  '.and(jsonBody[OrderNotFound].description("No order with this id."))' \
  '.summary("Get an order")' \
  '.description("Returns the order with its lines. Read-only.")' \
  'Docs.undocumented('; do
  grep -qF "$line" README.md && grep -qF "$line" "$SUITE" || {
    echo "FAIL: snippet line not in both README and suite: $line" >&2
    exit 1
  }
  echo "  PASS $line"
done

echo "PASS: Phase 2 (README convention) verified"
