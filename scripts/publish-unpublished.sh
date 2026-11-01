#!/usr/bin/env bash
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

# sbt project -> published artifact id (Scala 3 / sbt-plugin cross suffixes).
PROJECTS=(purerestlib pureKafka sbtPureKafkaDocs)
ARTIFACTS=(purerestlib_3 purekafka_3 sbt-purekafka-docs_2.12_1.0)

prints=()
for project in "${PROJECTS[@]}"; do prints+=("print $project/version"); done
VERSIONS=()
while IFS= read -r v; do VERSIONS+=("$v"); done < <(
  sbt -batch -error "${prints[@]}" < /dev/null | tail -n "${#PROJECTS[@]}"
)

to_publish=()
for i in "${!PROJECTS[@]}"; do
  project="${PROJECTS[$i]}" artifact="${ARTIFACTS[$i]}" version="${VERSIONS[$i]}"
  pom="$REGISTRY/$artifact/$version/$artifact-$version.pom"
  status=$(curl -s -o /dev/null -w '%{http_code}' -u "$GITHUB_ACTOR:$GITHUB_TOKEN" "$pom")
  case "$status" in
    200 | 302) echo "skip $project $version: already published" ;;
    404) echo "publish $project $version"; to_publish+=("$project/publish") ;;
    *) echo "FAIL: $pom returned HTTP $status" >&2; exit 1 ;;
  esac
done

if [ "${#to_publish[@]}" -eq 0 ]; then
  echo "nothing to publish"
elif [ -n "${DRY_RUN:-}" ]; then
  echo "dry run: would run sbt ${to_publish[*]}"
else
  sbt "${to_publish[@]}"
fi
