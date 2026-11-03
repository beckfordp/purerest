#!/usr/bin/env bash
set -euo pipefail

# Phase 3 verification for journey-telemetry-lib_20261103: purerestlib and
# purekafka <version> download from GitHub Packages and contain this track's
# classes — what each service's sbt build resolves next.
#
# Usage: ./scripts/verify-perf4a-release.sh [version]   (default 0.8.0)
# Needs GITHUB_TOKEN with read:packages, or a logged-in `gh`.

VERSION="${1:-0.8.0}"
TOKEN="${GITHUB_TOKEN:-$(gh auth token)}"
BASE="https://maven.pkg.github.com/beckfordp/purerest/io/github/beckfordp"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

check() { # module class...
  local module="$1"; shift
  curl -fsSL -H "Authorization: Bearer $TOKEN" -o "$WORK/$module.jar" \
    "$BASE/${module}_3/$VERSION/${module}_3-$VERSION.jar" || {
    echo "FAIL: $module $VERSION not downloadable from GitHub Packages" >&2
    exit 1
  }
  # Listing captured first: `unzip | grep -q` SIGPIPEs unzip under pipefail.
  local listing; listing="$(unzip -l "$WORK/$module.jar")"
  for cls in "$@"; do
    grep -q "$cls" <<< "$listing" || { echo "FAIL: $module $VERSION has no $cls" >&2; exit 1; }
    echo "  ok $module $VERSION: $cls"
  done
}

check purerestlib purerest/metrics/PoolMetrics purerest/logging/Milestones
check purekafka purekafka/consumer/EventAge
echo "PASS: purerestlib + purekafka $VERSION published"
