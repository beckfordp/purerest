#!/usr/bin/env bash
# SPDX-License-Identifier: MIT

set -euo pipefail

# Phase 3 verification for otlp-exporter_20261104: purerestlib <version>
# downloads from GitHub Packages with the tracing classes, and its POM pulls the
# OTLP exporter + JDK sender with okhttp excluded — what services resolve in S13.
#
# Usage: ./scripts/verify-perf5-release.sh [version]   (default 0.9.0)
# Needs GITHUB_TOKEN with read:packages, or a logged-in `gh`.

VERSION="${1:-0.9.0}"
TOKEN="${GITHUB_TOKEN:-$(gh auth token)}"
BASE="https://maven.pkg.github.com/beckfordp/purerest/io/github/beckfordp/purerestlib_3/$VERSION"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fetch() { # file
  curl -fsSL -H "Authorization: Bearer $TOKEN" -o "$WORK/$1" "$BASE/$1" || {
    echo "FAIL: $1 not downloadable from GitHub Packages" >&2
    exit 1
  }
}
fetch "purerestlib_3-$VERSION.jar"
fetch "purerestlib_3-$VERSION.pom"

# Listing captured first: `unzip | grep -q` SIGPIPEs unzip under pipefail.
listing="$(unzip -l "$WORK/purerestlib_3-$VERSION.jar")"
for cls in purerest/tracing/TracingConfig purerest/tracing/Tracing; do
  grep -q "$cls" <<< "$listing" || { echo "FAIL: no $cls in jar" >&2; exit 1; }
  echo "  ok jar: $cls"
done

pom="$(cat "$WORK/purerestlib_3-$VERSION.pom")"
for want in opentelemetry-exporter-otlp opentelemetry-exporter-sender-jdk \
  "<artifactId>opentelemetry-exporter-sender-okhttp</artifactId>"; do
  grep -qF "$want" <<< "$pom" || { echo "FAIL: POM lacks $want" >&2; exit 1; }
  echo "  ok pom: $want"
done
grep -q "<exclusions>" <<< "$pom" || { echo "FAIL: POM has no okhttp exclusion" >&2; exit 1; }
echo "PASS: purerestlib $VERSION published with OTLP exporter"
