#!/usr/bin/env bash
# SPDX-License-Identifier: MIT

set -euo pipefail

# Publishes each purerest module to GitHub Packages unless its version is
# already there. Used by release.yml: purerestlib/pureKafka take their version
# from the tag, but sbt-purekafka-docs has a fixed, hand-bumped version, so a
# release that doesn't touch the plugin would otherwise fail re-publishing it
# (overwrite is disabled) and skip deploy-docs (v0.4.0, v0.7.0).
#
# Needs GITHUB_ACTOR and GITHUB_TOKEN (read/write:packages).
#
# Usage: ./scripts/publish-unpublished.sh   (DRY_RUN=1 to only report)

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
REGISTRY="https://maven.pkg.github.com/beckfordp/purerest/io/github/beckfordp"

# Published artifact id per sbt project (Scala 3 / sbt-plugin cross suffixes).
artifact_of() {
  case "$1" in
    purerestlib) echo purerestlib_3 ;;
    pureKafka) echo purekafka_3 ;;
    sbtPureKafkaDocs) echo sbt-purekafka-docs_2.12_1.0 ;;
    *) echo "FAIL: no artifact id for sbt project $1" >&2; return 1 ;;
  esac
}

VERSIONS_FILE=target/publish-versions.txt
rm -f "$VERSIONS_FILE"
sbt -batch writePublishVersions < /dev/null
[ -s "$VERSIONS_FILE" ] || { echo "FAIL: $VERSIONS_FILE not written" >&2; exit 1; }

to_publish=()
while read -r project version; do
  artifact=$(artifact_of "$project")
  pom="$REGISTRY/$artifact/$version/$artifact-$version.pom"
  status=$(curl -s -o /dev/null -w '%{http_code}' -u "$GITHUB_ACTOR:$GITHUB_TOKEN" "$pom")
  case "$status" in
    200 | 302) echo "skip $project $version: already published" ;;
    404) echo "publish $project $version"; to_publish+=("$project/publish") ;;
    *) echo "FAIL: $pom returned HTTP $status" >&2; exit 1 ;;
  esac
done < "$VERSIONS_FILE"

if [ "${#to_publish[@]}" -eq 0 ]; then
  echo "nothing to publish"
elif [ -n "${DRY_RUN:-}" ]; then
  echo "dry run: would run sbt ${to_publish[*]}"
else
  sbt "${to_publish[@]}"
fi
