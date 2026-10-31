#!/usr/bin/env bash
set -euo pipefail

# Verifies producer-only, versionable schema registration (gluon TD-18.1/18.2)
# end-to-end against the real local Apicurio Registry (infra/k8s/local-infra),
# via a throwaway sbt project consuming locally published purekafka +
# sbt-purekafka-docs - same approach as verify-schema-registry-check.sh.
#
# Proves: a consumer mirror with no producer artifact warns and skips; the
# producer registers v1 with a producer label and BACKWARD_TRANSITIVE; an
# identical rerun adds nothing; an optional field registers v2; a breaking
# change fails; a compatible consumer mirror passes without writing; an
# incompatible one fails.
#
# Usage: ./scripts/verify-schema-registry-evolution.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

REGISTRY_URL="http://apicurio-registry.gluon-local.svc.cluster.local:8080"
API="$REGISTRY_URL/apis/registry/v3"
GROUP_ID="schema-registry-evolution-verify-$(date +%s)"
ARTIFACT="$API/groups/$GROUP_ID/artifacts/verify-topic-VerifyEvent"
FAILED=0

echo "1. Publishing purekafka + sbt-purekafka-docs to the local ivy cache..."
sbt --error "pureKafka/publishLocal; sbtPureKafkaDocs/publishLocal"
PUREKAFKA_VERSION="$(sbt --error -Dsbt.supershell=false 'print pureKafka/version' | tail -1)"
PLUGIN_VERSION="$(sbt --error -Dsbt.supershell=false 'print sbtPureKafkaDocs/version' | tail -1)"
echo "   purekafka $PUREKAFKA_VERSION / sbt-purekafka-docs $PLUGIN_VERSION"

TMP_DIR="$(mktemp -d)"
cleanup() {
  rm -rf "$TMP_DIR"
  curl -s -o /dev/null -X DELETE "$API/groups/$GROUP_ID" || true
}
trap cleanup EXIT

mkdir -p "$TMP_DIR/project" "$TMP_DIR/src/main/scala/fixture"
cp project/build.properties "$TMP_DIR/project/build.properties"
cat > "$TMP_DIR/project/plugins.sbt" <<EOF
addSbtPlugin("io.github.beckfordp" % "sbt-purekafka-docs" % "$PLUGIN_VERSION")
EOF
cat > "$TMP_DIR/build.sbt" <<EOF
import purekafka.sbtplugin.PureKafkaDocsPlugin.autoImport._
lazy val root = (project in file("."))
  .enablePlugins(purekafka.sbtplugin.PureKafkaDocsPlugin)
  .settings(
    scalaVersion := "3.9.0",
    libraryDependencies += "io.github.beckfordp" %% "purekafka" % "$PUREKAFKA_VERSION",
    asyncApiChannelsClass := "fixture.VerifyChannels",
    schemaRegistryUrl := "$REGISTRY_URL",
    schemaRegistryGroupId := "$GROUP_ID",
    schemaRegistryProducer := "verify-producer"
  )
EOF

# $1: payload fields, $2: direction
write_fixture() {
  cat > "$TMP_DIR/src/main/scala/fixture/VerifyChannels.scala" <<EOF
package fixture

import purekafka.docs.{AsyncApiChannel, AsyncApiChannelsProvider, ChannelDirection, PublisherKind}
import sttp.tapir.Schema

final case class VerifyPayload($1)
object VerifyPayload {
  implicit val schema: Schema[VerifyPayload] = Schema.derived[VerifyPayload]
}

object VerifyChannels extends AsyncApiChannelsProvider {
  val channels: List[AsyncApiChannel[?]] = List(
    AsyncApiChannel[VerifyPayload](topic = "verify-topic", eventType = "VerifyEvent", direction = $2)
  )
}
EOF
}

PRODUCER="ChannelDirection.Publish(PublisherKind.Cdc)"
CONSUMER="ChannelDirection.Subscribe"

run_check() { (cd "$TMP_DIR" && sbt --error schemaRegistryCheck); }
versions() { curl -s "$ARTIFACT/versions" | python3 -c "import json,sys;print(json.load(sys.stdin).get('count',0))" 2>/dev/null || echo 0; }
expect_ok() { if run_check; then echo "   OK: $1"; else echo "FAIL: $1" >&2; FAILED=1; fi; }
expect_fail() { if run_check; then echo "FAIL: $1" >&2; FAILED=1; else echo "   OK: $1"; fi; }
expect_versions() {
  local n; n="$(versions)"
  if [ "$n" = "$1" ]; then echo "   OK: $1 version(s)"; else echo "FAIL: expected $1 version(s), found $n" >&2; FAILED=1; fi
}

echo "2. Consumer mirror before any producer artifact (warn and skip)..."
write_fixture "message: String" "$CONSUMER"
expect_ok "absent producer artifact skipped"
expect_versions 0

echo "3. Producer v1..."
write_fixture "message: String" "$PRODUCER"
expect_ok "producer registered"
expect_versions 1
LABEL="$(curl -s "$ARTIFACT" | python3 -c "import json,sys;print(json.load(sys.stdin)['labels'].get('producer'))")"
RULE="$(curl -s "$ARTIFACT/rules/COMPATIBILITY" | python3 -c "import json,sys;print(json.load(sys.stdin)['config'])")"
[ "$LABEL" = "verify-producer" ] && echo "   OK: producer label" || { echo "FAIL: label '$LABEL'" >&2; FAILED=1; }
[ "$RULE" = "BACKWARD_TRANSITIVE" ] && echo "   OK: BACKWARD_TRANSITIVE" || { echo "FAIL: rule '$RULE'" >&2; FAILED=1; }
SCHEMA="$(curl -s "$ARTIFACT/versions/1/content" | python3 -c "import json,sys;print(json.load(sys.stdin)['\$schema'])")"
[ "$SCHEMA" = "http://json-schema.org/draft-07/schema#" ] && echo "   OK: draft-07" || { echo "FAIL: \$schema '$SCHEMA'" >&2; FAILED=1; }

echo "4. Identical producer rerun..."
expect_ok "identical rerun"
expect_versions 1

echo "5. Producer adds an optional field (compatible)..."
write_fixture "message: String, note: Option[String]" "$PRODUCER"
expect_ok "optional field registered"
expect_versions 2

echo "6. Producer breaking change (message String -> Int)..."
write_fixture "message: Int, note: Option[String]" "$PRODUCER"
expect_fail "breaking change rejected"
expect_versions 2

echo "7. Compatible consumer mirror (subset)..."
write_fixture "message: String" "$CONSUMER"
expect_ok "compatible mirror passes"
expect_versions 2

echo "8. Incompatible consumer mirror (extra required field)..."
write_fixture "message: String, extra: String" "$CONSUMER"
expect_fail "incompatible mirror rejected"
expect_versions 2

if [ "$FAILED" -eq 0 ]; then echo; echo "All checks passed."; else echo; echo "One or more checks failed - see FAIL lines above." >&2; exit 1; fi
