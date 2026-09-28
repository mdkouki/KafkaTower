#!/usr/bin/env bash
# One-shot topic bootstrap for the local dev Kafka broker. Runs BEFORE init-acls.sh, as the
# 'admin' superuser (bypasses ACL checks entirely).
#
# Why this exists instead of relying on auto.create.topics.enable=true (still set on the
# broker): with KAFKA_ALLOW_EVERYONE_IF_NO_ACL_FOUND=false, a producer/consumer implicitly
# triggering topic auto-creation needs a Create ACL on that topic — and none of the demo
# principals (order-service, fulfillment-service, logistics-service) are granted Create, only Write/Read/Describe (see
# init-acls.sh). Without the topic already existing, their first produce/consume fails with
# TopicAuthorizationException even though their Write/Read ACL is otherwise correct. Creating
# every topic up front as the superuser sidesteps that entirely.
#
# The topic list here is every concrete topic name referenced by compose.yml's DEMO_PRODUCES/
# DEMO_CONSUMES env vars and by init-acls.sh's grant_produce/grant_consume calls — kept in sync
# with those by hand. PREFIXED-only entries (fulfillment-service's "shipments.*" consume declaration) don't
# need their own line here: the concrete topic that prefix covers (logistics-service's shipments.finance) is
# already created below.
set -euo pipefail

BOOTSTRAP_SERVER="kafka:29092"
CONFIG_FILE="$(mktemp)"
trap 'rm -f "$CONFIG_FILE"' EXIT

cat > "$CONFIG_FILE" <<EOF
security.protocol=SASL_PLAINTEXT
sasl.mechanism=PLAIN
sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="admin" password="admin-secret";
EOF

topics() {
  # /opt/kafka/bin is NOT on PATH in this image (same as init-acls.sh) — absolute path regardless of cwd.
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVER" --command-config "$CONFIG_FILE" "$@"
}

TOPIC_NAMES=(
  # order-service
  orders.commands.confirmed
  fulfillment.updates
  fulfillment.command-replies
  inventory.production
  orders.commands.provider
  orders.commands.participant
  orders.events
  fulfillment.commands
  inventory.snapshots
  finance.settlements
  # fulfillment-service
  platform.events
  fulfillment.client-updates
  fulfillment.fast-track
  # logistics-service
  shipments.finance
  orders.commands.provider.v2
  fulfillment.updates.v2
  inventory.snapshots.v2
  logistics.status
)

echo "Waiting for the broker's authorizer to be ready..."
until topics --list >/dev/null 2>&1; do
  sleep 2
done

for topic in "${TOPIC_NAMES[@]}"; do
  echo "== ensuring topic exists: $topic =="
  topics --create --if-not-exists --topic "$topic" --partitions 3 --replication-factor 1
done

echo "Topic bootstrap complete."
