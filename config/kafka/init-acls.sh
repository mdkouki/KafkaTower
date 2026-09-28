#!/usr/bin/env bash
# One-shot ACL bootstrap for the local dev Kafka broker (SASL_PLAINTEXT/PLAIN, StandardAuthorizer,
# allow.everyone.if.no.acl.found=false — see compose.yml's kafka/acl-init services). Runs as the
# 'admin' superuser (bypasses ACL checks entirely) so it can grant everyone else's ACLs before
# any of them exist. `kafka-acls.sh --add` is idempotent — safe to rerun (e.g. on a restart).
#
# Every non-superuser principal below gets exactly what it needs and nothing more:
#   - kafkatower, akhq, kminion, kafka-exporter: read-only "observer" access (this app's own
#     ACL registry, and every monitoring/UI tool, only ever inspects — never produces, consumes,
#     or alters anything) — Describe/DescribeConfigs/Read on every topic and group, Describe on
#     the cluster and on every transactional ID.
#   - order-service, fulfillment-service, logistics-service: exactly the topics/groups compose.yml's DEMO_PRODUCES/DEMO_CONSUMES/
#     DEMO_CONSUMER_GROUP declare for them, nothing else. fulfillment-service's "shipments.*" entry becomes a
#     PREFIXED ACL on the "shipments" prefix (a literal ".*"-suffixed topic name isn't what's meant
#     here — it mirrors the same PREFIXED-pattern convention AclGraphBuilder displays with a
#     trailing '*'). order-service additionally gets a transactional-id ACL to demo that field.
set -euo pipefail

BOOTSTRAP_SERVER="kafka:29092"
CONFIG_FILE="$(mktemp)"
trap 'rm -f "$CONFIG_FILE"' EXIT

cat > "$CONFIG_FILE" <<EOF
security.protocol=SASL_PLAINTEXT
sasl.mechanism=PLAIN
sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="admin" password="admin-secret";
EOF

acls() {
  # /opt/kafka/bin is NOT on PATH in this image — verified via `docker exec acl-init which
  # kafka-acls.sh` (not found); the script lives at this absolute path regardless of cwd.
  /opt/kafka/bin/kafka-acls.sh --bootstrap-server "$BOOTSTRAP_SERVER" --command-config "$CONFIG_FILE" "$@"
}

grant_observer() {
  local principal="User:$1"
  acls --add --allow-principal "$principal" --operation Describe --operation DescribeConfigs --operation Read --topic '*'
  acls --add --allow-principal "$principal" --operation Describe --operation Read --group '*'
  acls --add --allow-principal "$principal" --operation Describe --cluster
  acls --add --allow-principal "$principal" --operation Describe --transactional-id '*'
}

grant_produce() {
  local principal="User:$1"
  shift
  # kafka-acls.sh's --topic flag takes exactly one value per occurrence — passing multiple
  # topic names as "$@" under a single --topic silently drops everything but the first, so
  # each topic needs its own --topic flag repeated in the same invocation.
  local topic_flags=()
  for t in "$@"; do topic_flags+=(--topic "$t"); done
  acls --add --allow-principal "$principal" --operation Write --operation Describe "${topic_flags[@]}"
}

grant_consume() {
  local principal="User:$1"
  shift
  local topic_flags=()
  for t in "$@"; do topic_flags+=(--topic "$t"); done
  acls --add --allow-principal "$principal" --operation Read --operation Describe "${topic_flags[@]}"
}

grant_consume_prefixed() {
  local principal="User:$1"
  local prefix="$2"
  acls --add --allow-principal "$principal" --operation Read --operation Describe --resource-pattern-type prefixed --topic "$prefix"
}

grant_group() {
  local principal="User:$1"
  local group="$2"
  acls --add --allow-principal "$principal" --operation Read --group "$group"
}

echo "Waiting for the broker's authorizer to be ready..."
until acls --list --topic '*' >/dev/null 2>&1; do
  sleep 2
done

echo "== kafkatower (this app's own ACL registry / AdminClient) =="
grant_observer kafkatower

echo "== monitoring tools (read-only observers) =="
grant_observer akhq
grant_observer kminion
grant_observer kafka-exporter

echo "== order-service =="
grant_produce order-service orders.commands.confirmed fulfillment.updates fulfillment.command-replies inventory.production
grant_consume order-service orders.commands.provider orders.commands.participant orders.events fulfillment.commands inventory.snapshots finance.settlements
grant_group order-service order-service-group
acls --add --allow-principal User:order-service --operation Write --operation Describe --resource-pattern-type prefixed --transactional-id order-txn

echo "== fulfillment-service =="
grant_produce fulfillment-service platform.events fulfillment.client-updates fulfillment.commands
grant_consume fulfillment-service fulfillment.fast-track platform.events fulfillment.client-updates
grant_consume_prefixed fulfillment-service shipments
grant_group fulfillment-service fulfillment-service-group

echo "== logistics-service =="
grant_produce logistics-service shipments.finance orders.commands.provider.v2 fulfillment.updates.v2 orders.events fulfillment.fast-track platform.events logistics.status
grant_consume logistics-service shipments.finance orders.commands.provider.v2 fulfillment.updates.v2 orders.events inventory.snapshots.v2 platform.events logistics.status
grant_group logistics-service logistics-service-group

echo "ACL bootstrap complete."
