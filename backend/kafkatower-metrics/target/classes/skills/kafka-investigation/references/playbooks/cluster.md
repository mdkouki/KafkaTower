# Investigation Playbook — Cluster

**Goal**: High-level health snapshot before drilling into subsystems.

**Do not call `describeTopic` per topic here.** This playbook answers cluster-wide health with
cluster/broker-level admin calls and aggregate PromQL only (`sum(...)`, `count(...)`, no `by
(topic)` unless a step below says so) — never one `describeTopic` call per topic in the cluster.
Only drill into a specific topic with `describeTopic` if Step 5 flags it (under-replicated /
at-min-ISR) or the user named it.

### Step 1 — Admin: cluster overview

```
describeCluster(cluster)
```

Check: broker count, controller ID, cluster ID. Note any missing brokers immediately.

### Step 2 — Admin: broker configs (sample one broker)

```
describeBrokerConfigs(cluster, brokerId)
```

Key configs: `num.io.threads`, `num.network.threads`, `log.dirs`, `default.replication.factor`.

### Step 3 — Metrics: active broker count and exporter health

```promql
# Confirm KMinion is up
kminion_exporter_up{cluster="<name>"}

# Controller view of active brokers (compare to describeCluster output)
kafka_controller_kafkacontroller_activebrokercount{cluster="<name>"}
```

### Step 4 — Metrics: protocol error rate ⚠️ PRIMARY error signal (always before partition metrics)

```promql
# Step 4a — Is there any error activity? (start here — if zero, skip 4b–4d)
sum(rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error!="NONE"}[5m]))

# Step 4b — Which request types are failing?
sum by (request) (rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error!="NONE"}[5m]))

# Step 4c — Which error codes are returned?
sum by (error) (rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error!="NONE"}[5m]))

# Step 4d — Which broker(s) are producing errors?
sum by (broker) (rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error!="NONE"}[5m]))
```

> If Step 4a returns non-zero: note which request types, error codes, and brokers, then
> proceed to Step 5. If Step 4a is zero, skip 4b–4d and go directly to Step 5.

### Step 5 — Metrics: partition replication health (critical signals)

```promql
# Under-replicated partitions cluster-wide (non-zero = degraded)
sum(kafka_cluster_partition_underreplicated{cluster="<name>"})

# Partitions at min-ISR threshold (data loss risk if one more replica drops — "at risk", not "broken")
sum(kafka_cluster_partition_atminisr{cluster="<name>"})

# Partitions BELOW min-ISR — acks=all writes are failing right now. Unconfirmed against a live
# instance (see the kafka-core metrics catalog); if this metric isn't exported, the query below
# returns empty rather than erroring, so fall back to the ISR-vs-replica comparison beneath it.
sum(kafka_cluster_partition_underminisr{cluster="<name>"})

# ISR size vs replica count — partitions where ISR has shrunk (fallback for the above)
kafka_cluster_partition_insyncreplicascount{cluster="<name>"} < kafka_cluster_partition_replicascount{cluster="<name>"}
```

> If `sum(kafka_cluster_partition_underreplicated{cluster="<name>"}) > 0` → escalate immediately. Fetch this playbook's "topic" scope for the affected topics before continuing.
> If `sum(kafka_cluster_partition_underminisr{cluster="<name>"}) > 0` (or the ISR-vs-replica fallback finds any match) → writes are failing now, escalate immediately regardless of the under-replicated count.

### Step 6 — Metrics: traffic overview

```promql
# Bytes received/sent per broker
kminion_kafka_received_bytes{cluster="<name>"}
kminion_kafka_sent_bytes{cluster="<name>"}

# Request rate per broker
rate(kminion_kafka_requests_received_total{cluster="<name>"}[5m])
```

### Step 7 — Metrics: request handler saturation (if throughput looks abnormal)

```promql
# Request handler idle ratio (< 30% = saturation, < 10% = critical)
kafka_server_kafkarequesthandlerpool_requesthandleravgidlepercent{cluster="<name>"}
```

### Cluster Checklist

- [ ] All expected brokers present in describeCluster and controller count
- [ ] `sum(kafka_network_request_metrics_errorpersec{cluster="<name>", error!="NONE"}) == 0` (Step 4a)
- [ ] `sum(kafka_cluster_partition_underreplicated{cluster="<name>"}) == 0`
- [ ] `sum(kafka_cluster_partition_underminisr{cluster="<name>"}) == 0` — writes are failing if this is non-zero. `atminisr` is expected to be occasionally non-zero (one replica loss from that point, not an active failure) and is not a hard gate on its own.
- [ ] Traffic rate is normal (no sudden spikes or drops)
- [ ] Request handler idle > 30%
