#!/usr/bin/env bash
set -euo pipefail

# Phase 2 verification for latency-buckets_20261030: purerestlib <version>
# is downloadable from GitHub Packages, and its jar contains LatencyBuckets —
# what each service's sbt build will resolve in Phase 3.
#
# Usage: ./scripts/verify-perf3a-phase2.sh [version]   (default 0.4.0)
# Needs GITHUB_TOKEN with read:packages, or a logged-in `gh`.

VERSION="${1:-0.4.0}"
TOKEN="${GITHUB_TOKEN:-$(gh auth token)}"
BASE="https://maven.pkg.github.com/beckfordp/purerest/io/github/beckfordp/purerestlib_3/$VERSION"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "== Downloading purerestlib_3-$VERSION.jar =="
curl -fsSL -H "Authorization: Bearer $TOKEN" -o "$WORK/lib.jar" "$BASE/purerestlib_3-$VERSION.jar" || {
  echo "FAIL: purerestlib $VERSION not downloadable from GitHub Packages" >&2
  exit 1
}

# Listing captured first: `unzip | grep -q` SIGPIPEs unzip under pipefail.
listing="$(unzip -l "$WORK/lib.jar")"
if grep -q "purerest/metrics/LatencyBuckets" <<< "$listing"; then
  echo "PASS: purerestlib $VERSION published with LatencyBuckets"
else
  echo "FAIL: purerestlib $VERSION jar has no LatencyBuckets" >&2
  exit 1
fi
