#!/usr/bin/env bash
set -euo pipefail

# Manual verification for Phase 2 of the scaladoc_20260925 track: confirms the
# GitHub Pages Scaladoc site (deployed by .github/workflows/release.yml's
# deploy-docs job) is live and serving real content, and that GitHub Pages
# itself is still configured to deploy via Actions. Does not trigger a new
# release — that was done once, manually, with a real test tag (see plan.md).
#
# Usage: ./scripts/verify-scaladoc-pages-deploy.sh

SITE_URL="https://beckfordp.github.io/purerest"
FAILED=0

echo "1. Confirming GitHub Pages is configured to build from GitHub Actions..."
BUILD_TYPE="$(gh api repos/beckfordp/purerest/pages --jq '.build_type' 2>/dev/null || true)"
if [ "$BUILD_TYPE" = "workflow" ]; then
  echo "   OK: Pages build_type is 'workflow'"
else
  echo "   FAIL: expected Pages build_type 'workflow', got '${BUILD_TYPE:-<none>}'" >&2
  FAILED=1
fi

echo
echo "2. Confirming the site root is reachable..."
ROOT_STATUS="$(curl -s -o /dev/null -w '%{http_code}' "$SITE_URL/")"
if [ "$ROOT_STATUS" = "200" ]; then
  echo "   OK: $SITE_URL/ returned 200"
else
  echo "   FAIL: $SITE_URL/ returned $ROOT_STATUS" >&2
  FAILED=1
fi

echo
echo "3. Confirming a real package page is reachable and looks like Scaladoc..."
PAGE_BODY="$(curl -s "$SITE_URL/purerest/tracing.html")"
if echo "$PAGE_BODY" | grep -q "Tracer construction for purerest"; then
  echo "   OK: tracing.html contains expected Scaladoc content"
else
  echo "   FAIL: tracing.html did not contain the expected content" >&2
  FAILED=1
fi

echo
if [ "$FAILED" -eq 0 ]; then
  echo "All checks passed."
else
  echo "One or more checks FAILED. See above." >&2
  exit 1
fi
