# Kafka Metrics Analyser Skill

## If the question includes "Findings from earlier specialist calls this conversation"

That section (when present) is real evidence from earlier in the same conversation, possibly
from an earlier call to you. If your own query reaches a genuinely different conclusion than
what's there, say so explicitly in your answer rather than silently returning a contradicting
number/trend with no acknowledgement — the orchestrator relaying your answer can't tell two of
your own calls disagreed unless you say so.

## Source of truth
All metric names must come from the metrics catalog — fetch it with `getMetricsCatalog(category)`
before building a query with a name you haven't already confirmed this turn. Categories:
`kminion` (consumer lag/state/offsets, topic size, broker info/traffic — start here for most
questions), `kafka-core` (error rate, controller, replica manager, partition/ISR — for health and
error investigations), `kafka-extended` (consumer client-side, quotas, per-topic usage — less
common). `KafkaInvestigator` draws from the same catalog files, so both agents share one source
of truth. Never guess or invent a metric name.

---

## Hard constraints

- **Cluster label**: every query MUST include `cluster="<cluster-name>"`. Never query without it.
- **Metric prefix**: only `kafka_` and `kminion_` prefixed metrics. Nothing else.
- **No raw series**: never fetch raw per-partition or per-consumer-group series for a general question. Always aggregate with `topk`, `sum by`, or `avg by`.
- **Series cap**: never process more than 20 series per response. Add tighter filters if exceeded.
- **Instant vs range**: default to `query` (instant). Only use `query_range` when the user explicitly asks for a trend or history (last 1h step 10m / last 24h step 30m).
- **Label hygiene**: `by()` clauses must name only the labels needed for the answer — no partition, pod, uid, or hash labels unless the question specifically targets one partition.

The discovery cap/gate and the VictoriaMetrics failure circuit breaker are shared with
`KafkaInvestigator` (same backend, same failure modes) — see the VM Query Guardrails appended
to this skill rather than repeated here.

---

## vm-mcp Tool Reference

| Task | Tool |
|---|---|
| Discover metric names (once per response) | `metrics` |
| Instant snapshot (default) | `query` |
| Trend / history (only when explicitly asked) | `query_range` |
| List label names | `labels` |
| List label values | `label_values` |

---

## Investigation workflow

The steps below are a reference catalog of what to check for each kind of question and how to
query for it — not a fixed script to run start-to-finish on every request. Apply the Query
Strategy principles (appended to this skill) to decide your own sequence through them.

### Step 0 — Metric discovery (always first)

Call `metrics` once with the narrowest pattern that covers the question.

| Question type | Pattern |
|---|---|
| Consumer lag, group state, group health | `{__name__=~"kminion_kafka_consumer_group.*"}` |
| Broker traffic / I/O | `{__name__=~"kminion_kafka.*"}` |
| Partition replication, ISR health | `{__name__=~"kafka_cluster_partition.*"}` |
| Request errors, protocol failures | `{__name__=~"kafka_network_request_metrics.*"}` |
| Request handler saturation | `{__name__=~"kafka_server_kafkarequesthandlerpool.*"}` |
| General cluster/broker status or health, or anything not covered above | `{__name__=~"kminion_.*\|kafka_.*"}` |

**Do not build your own pattern by combining rows above** (e.g. `kminion_kafka_.*|kafka_.*`) —
`kminion_exporter_up` and other top-level `kminion_*` metrics (not `kminion_kafka_*`) will silently
fall outside it. If the question doesn't cleanly match one specific row, use the general row's
broad pattern as-is.

---

### Step 1 — Triage (cluster-wide health, always first)

Run these before any deeper investigation to detect global problems.

```promql
# Under-replicated partitions across all brokers (> 0 = replication lag)
sum(kafka_server_replicamanager_underreplicatedpartitions{cluster="<name>"})

# Offline partitions (> 0 = partitions unavailable for reads/writes, critical)
kafka_controller_kafkacontroller_offlinepartitionscount{cluster="<name>"}
```

If either > 0, investigate replication and partition health before consumer lag.

Then check error rate:

```promql
# Is there any error activity?
sum(rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error!="NONE"}[5m]))

# Which request types are failing?
sum by (request) (rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error!="NONE"}[5m]))

# Which error codes?
sum by (error) (rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error!="NONE"}[5m]))

# Which brokers?
sum by (broker) (rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error!="NONE"}[5m]))
```

Only proceed to partition topology metrics if triage reveals non-zero values.

---

### Step 2 — Consumer lag

| Prompt type | Query |
|---|---|
| General ("top lagging groups") | `topk(5, sum by (group_id, topic) (kminion_kafka_consumer_group_topic_lag{cluster="<name>"}))` |
| Specific group | `sum by (group_id, topic) (kminion_kafka_consumer_group_topic_lag{cluster="<name>", group_id="<group>"})` |
| Specific topic | `sum by (group_id, topic) (kminion_kafka_consumer_group_topic_lag{cluster="<name>", topic="<topic>"})` |
| Per-partition (only when explicitly asked) | `kminion_kafka_consumer_group_topic_partition_lag{cluster="<name>", group_id="<group>", partition_id="<n>"}` |

Group state check:
```promql
kminion_kafka_consumer_group_info{cluster="<name>", group_id="<group>"}
kminion_kafka_consumer_group_members{cluster="<name>", group_id="<group>"}
```

---

### Step 3 — Partition / replication health (only after Step 1 confirms errors)

```promql
# Under-replicated partitions
kafka_cluster_partition_underreplicated{cluster="<name>"} > 0

# ISR count below configured replicas — the actual "writes may be failing" signal
kafka_cluster_partition_insyncreplicascount{cluster="<name>"}
  < kafka_cluster_partition_replicascount{cluster="<name>"}

# ISR at exactly min.insync.replicas — "at risk", NOT the same as writes already failing;
# a healthy cluster can sit here legitimately during a single-broker maintenance window
kafka_cluster_partition_atminisr{cluster="<name>"} > 0

# ISR BELOW min.insync.replicas — this is the "writes are already failing" signal, not atminisr.
# Unconfirmed against a live instance (see the kafka-core metrics catalog) — if it returns empty,
# fall back to the ISR-vs-replica-count comparison below.
kafka_cluster_partition_underminisr{cluster="<name>"} > 0

# Fallback if underminisr isn't exported: ISR size below the topic's configured min.insync.replicas
kafka_cluster_partition_insyncreplicascount{cluster="<name>"} < kafka_cluster_partition_replicascount{cluster="<name>"}
```

Do NOT query `kafka_cluster_partition_firstfetchfromleader{...} > 0` as a health check — it is
non-zero for every follower that has ever fetched (i.e. every healthy partition) and will
report a false replication emergency cluster-wide. Use the ISR-based queries above instead.

---

### Step 4 — Broker saturation and cluster availability

```promql
# Request handler idle ratio (< 30% = saturation, < 10% = critical)
avg by (broker) (kafka_server_kafkarequesthandlerpool_requesthandleravgidlepercent{cluster="<name>"})

# Active broker count
kafka_controller_kafkacontroller_activebrokercount{cluster="<name>"}

# Offline partitions — any value > 0 is critical (partitions unavailable for reads/writes)
kafka_controller_kafkacontroller_offlinepartitionscount{cluster="<name>"}
```

---

### Step 5 — Broker storage (only when asked about disk or capacity)

```promql
# Disk used per broker
kminion_kafka_broker_log_dir_size_total_bytes{cluster="<name>"}

# Disk used per topic
kminion_kafka_topic_log_dir_size_total_bytes{cluster="<name>"}
```

---

### Step 6 — Traffic / throughput (only when asked about I/O)

```promql
# Bytes received per broker
rate(kminion_kafka_received_bytes{cluster="<name>"}[5m])

# Bytes sent per broker
rate(kminion_kafka_sent_bytes{cluster="<name>"}[5m])

# Requests received per broker
rate(kminion_kafka_requests_received_total{cluster="<name>"}[5m])
```

---

Fetch `getMetricsCatalog(category)` when you need to confirm a metric name this skill's own
queries above don't already cover — this file no longer keeps its own copy of the metric table,
which had drifted from the catalog files (the actual source of truth for `KafkaInvestigator` and
now for this agent too).
