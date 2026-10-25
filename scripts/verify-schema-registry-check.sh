#!/usr/bin/env bash
set -euo pipefail

# Verifies the schemaRegistryCheck sbt task end-to-end (Rework 5 step 2, ADR
# 0003) against the real local Apicurio Registry instance (infra/k8s/local-infra).
#
# sbt AutoPlugins are only discoverable from a build's own meta-build classpath
# (project/plugins.sbt), not from a sibling project's output within the same
# build (see sbtPureKafkaDocs's own version-pinning comment in build.sbt) - so
# kafka-harness, living in this same build, can't just `enablePlugins` it.
# Exercising the task for real instead means publishing purekafka and
# sbt-purekafka-docs to the local ivy cache and consuming them from a
# throwaway sbt project, the same way a real external consumer (order-service,
# etc.) eventually will via a normal addSbtPlugin coordinate - not a
# production change to kafka-harness.
#
# Proves: first run registers the schema and sets a BACKWARD compatibility
# rule; a second identical run is a no-op (no error); a deliberately
# incompatible payload change is rejected and fails the sbt task.
#
# Usage: ./scripts/verify-schema-registry-check.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

REGISTRY_URL="http://apicurio-registry.gluon-local.svc.cluster.local:8080"
GROUP_ID="schema-registry-check-verify-$(date +%s)"
FAILED=0

echo "1. Publishing purekafka + sbt-purekafka-docs to the local ivy cache..."
sbt --error "pureKafka/publishLocal; sbtPureKafkaDocs/publishLocal"

PUREKAFKA_VERSION="$(sbt --error -Dsbt.supershell=false 'print pureKafka/version' | tail -1)"
PLUGIN_VERSION="$(sbt --error -Dsbt.supershell=false 'print sbtPureKafkaDocs/version' | tail -1)"
echo "   purekafka $PUREKAFKA_VERSION / sbt-purekafka-docs $PLUGIN_VERSION"

TMP_DIR="$(mktemp -d)"
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT

mkdir -p "$TMP_DIR/project" "$TMP_DIR/src/main/scala/fixture"
cp project/build.properties "$TMP_DIR/project/build.properties"

cat > "$TMP_DIR/project/plugins.sbt" <<EOF
addSbtPlugin("io.github.beckfordp" % "sbt-purekafka-docs" % "$PLUGIN_VERSION")
EOF

cat > "$TMP_DIR/build.sbt" <<EOF
lazy val root = (project in file("."))
  .enablePlugins(purekafka.sbtplugin.PureKafkaDocsPlugin)
  .settings(
    scalaVersion := "3.9.0",
    libraryDependencies += "io.github.beckfordp" %% "purekafka" % "$PUREKAFKA_VERSION",
    purekafka.sbtplugin.PureKafkaDocsPlugin.autoImport.asyncApiChannelsClass := "fixture.VerifyChannels",
    purekafka.sbtplugin.PureKafkaDocsPlugin.autoImport.schemaRegistryUrl := "$REGISTRY_URL",
    purekafka.sbtplugin.PureKafkaDocsPlugin.autoImport.schemaRegistryGroupId := "$GROUP_ID"
  )
EOF

write_fixture() {
  local field_type="$1"
  cat > "$TMP_DIR/src/main/scala/fixture/VerifyChannels.scala" <<EOF
package fixture

import purekafka.docs.{AsyncApiChannel, AsyncApiChannelsProvider}
import sttp.tapir.Schema

final case class VerifyPayload(message: $field_type)
object VerifyPayload {
  implicit val schema: Schema[VerifyPayload] = Schema.derived[VerifyPayload]
}

object VerifyChannels extends AsyncApiChannelsProvider {
  val channels: List[AsyncApiChannel[?]] = List(
    AsyncApiChannel[VerifyPayload](topic = "verify-topic", eventType = "VerifyEvent")
  )
}
EOF
}

run_check() {
  (cd "$TMP_DIR" && sbt --error schemaRegistryCheck)
}

echo "2. First run (registers + sets the BACKWARD rule)..."
write_fixture "String"
if run_check; then
  echo "   OK: first run succeeded"
else
  echo "FAIL: first run should have succeeded" >&2
  FAILED=1
fi

echo "3. Second, identical run (should be a no-op, not an error)..."
if run_check; then
  echo "   OK: second run succeeded"
else
  echo "FAIL: second identical run should have succeeded" >&2
  FAILED=1
fi

echo "4. Third run with an incompatible payload change (String -> Int)..."
write_fixture "Int"
if run_check; then
  echo "FAIL: incompatible schema change should have failed the task" >&2
  FAILED=1
else
  echo "   OK: incompatible schema change correctly failed the task"
fi

if [ "$FAILED" -eq 0 ]; then
  echo
  echo "All checks passed."
  exit 0
else
  echo
  echo "One or more checks failed - see FAIL lines above." >&2
  exit 1
fi
