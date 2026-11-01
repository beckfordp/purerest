#!/usr/bin/env bash
set -euo pipefail

# Phase 3 verification for rest-docs_20261101 (gluon TECHNICAL_DEBT.md TD-20.1):
# purerestlib <version> is downloadable from GitHub Packages with DocGap in its
# jar, and the Scaladoc site (deploy-docs) documents Docs.undocumented.
#
# Usage: ./scripts/verify-td20-1-phase3.sh [version]   (default 0.7.2)
# Needs GITHUB_TOKEN with read:packages, or a logged-in `gh`.

VERSION="${1:-0.7.2}"
TOKEN="${GITHUB_TOKEN:-$(gh auth token)}"
BASE="https://maven.pkg.github.com/beckfordp/purerest/io/github/beckfordp/purerestlib_3/$VERSION"
SITE="https://beckfordp.github.io/purerest/purerest/docs/Docs\$.html"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "== Downloading purerestlib_3-$VERSION.jar =="
curl -fsSL -H "Authorization: Bearer $TOKEN" -o "$WORK/lib.jar" "$BASE/purerestlib_3-$VERSION.jar" || {
  echo "FAIL: purerestlib $VERSION not downloadable from GitHub Packages" >&2
  exit 1
}

# Listing captured first: `unzip | grep -q` SIGPIPEs unzip under pipefail.
listing="$(unzip -l "$WORK/lib.jar")"
if grep -q "purerest/docs/DocGap" <<< "$listing"; then
  echo "  PASS purerestlib $VERSION published with DocGap"
else
  echo "FAIL: purerestlib $VERSION jar has no DocGap" >&2
  exit 1
fi

echo "== Scaladoc site =="
page="$(curl -fsSL "$SITE")" || {
  echo "FAIL: $SITE not reachable" >&2
  exit 1
}
if grep -q "undocumented" <<< "$page"; then
  echo "  PASS $SITE documents undocumented"
else
  echo "FAIL: $SITE has no undocumented" >&2
  exit 1
fi

echo "PASS: Phase 3 (release $VERSION) verified"
