# Kafka Metrics Catalog — Core Health Signals (`kafka_*`)

(Cluster-label and no-guessing-names rules are in your system prompt, not repeated per category
here.)

> **Verification status:** these `kafka_*` (JMX-exporter-derived) entries are NOT currently
> verifiable against a live instance — the local/dev VictoriaMetrics backend exposes zero
> `kafka_*` series right now (the Kafka JMX exporter isn't scraping in that environment). Treat
> any `kafka_*` name below as provisional until confirmed with `label_values(__name__)` filtered
> on `kafka_` against the target cluster's real VictoriaMetrics instance, and correct this file
> once confirmed.

---

## Kafka — Error Rate (`kafka_network_request_metrics_*`) ⚠️ PRIMARY error signal

> **Investigation priority:** always query `kafka_network_request_metrics_errorpersec` **first** when investigating cluster errors. This metric captures real-time protocol-level failures across all request types and brokers, giving the most direct signal of active problems. Only fall back to partition topology metrics (`kafka_cluster_partition_*`) once error rate is non-zero and you need to correlate which topics or partitions are affected.

| Metric | Key labels | Description |
|---|---|---|
| `kafka_network_request_metrics_errorpersec` | `cluster`, `request`, `error`, `broker` | Per-second error rate by request type and error code. Filter `error!="NONE"` to exclude successful responses. |

**Recommended investigation sequence:**

```promql
# Step 1 — Is there any error activity? (start here)
sum(rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error!="NONE"}[5m]))

# Step 2 — Which request types are failing?
sum by (request) (rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error!="NONE"}[5m]))

# Step 3 — Which error codes are returned?
sum by (error) (rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error!="NONE"}[5m]))

# Step 4 — Which broker(s) are producing errors?
sum by (broker) (rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error!="NONE"}[5m]))
```

---

## Kafka — Controller (`kafka_controller_*`)

| Metric                                                    | Key labels | Description                                                                                                                                |
|-----------------------------------------------------------|---|--------------------------------------------------------------------------------------------------------------------------------------------|
| `kafka_controller_kafkacontroller_activecontrollercount`  | `cluster` | Active controller count from the controller's view                                                                                         |
| `kafka_controller_kafkacontroller_activebrokercount`      | `cluster` | Active broker count from the controller's view                                                                                             |
| `kafka_controller_kafkacontroller_offlinepartitionscount` | `cluster` | Number of partitions with no online leader. Any value > 0 means those partitions are unavailable for reads and writes — treat as critical. |

Many other `kafka_controller_*` metrics are present (election rates, partition counts, etc.).

---

## Kafka — Replica Manager (`kafka_server_replicamanager_*`)

> **Triage vs drill-down:** use `kafka_server_replicamanager_underreplicatedpartitions` for cluster-wide triage (fast, broker-level count). Use `kafka_cluster_partition_underreplicated` (below) only to find *which specific partitions* are affected, after triage confirms a non-zero count.

| Metric | Key labels | Description |
|---|---|---|
| `kafka_server_replicamanager_underreplicatedpartitions` | `cluster`, `broker` | Number of under-replicated partitions reported by this broker. `sum()` across brokers for a cluster-wide count. Any value > 0 indicates replication lag — start here, not with the per-partition metric. |

---

## Kafka — Cluster Partition Topology (`kafka_cluster_partition_*`)

> **High cardinality:** these metrics emit one series per partition. Always filter for non-zero values to avoid scanning the full time series set. Use `> 0` in instant queries or wrap with `count() by (topic)` to aggregate.

### Partition Health — Error Detection

> **Use after triage:** only query these high-cardinality per-partition metrics after `kafka_server_replicamanager_underreplicatedpartitions` confirms a non-zero count. They tell you *which* partitions are affected, not whether there is a problem.

| Metric | Key labels | Description |
|---|---|---|
| `kafka_cluster_partition_underreplicated` | `cluster`, `topic`, `partition` | 1 if this partition is under-replicated. Active problem signal — query as `kafka_cluster_partition_underreplicated{cluster="<name>"} > 0` |
| `kafka_cluster_partition_atminisr` | `cluster`, `topic`, `partition` | 1 if ISR has dropped to exactly `min.insync.replicas` — the last replica loss the partition can absorb before `acks=all` writes start failing. This is NOT the same as writes already failing (see `underminisr` below) — a healthy RF=3/min.isr=2 cluster legitimately sits at `atminisr=1` whenever one broker is down for maintenance. Query as `kafka_cluster_partition_atminisr{cluster="<name>"} > 0` and treat as "at risk", not "broken". |
| `kafka_cluster_partition_underminisr` | `cluster`, `topic`, `partition` | 1 if ISR has dropped BELOW `min.insync.replicas` — `acks=all` producers are now getting `NOT_ENOUGH_REPLICAS`. This, not `atminisr`, is the "writes are failing" signal. **Unconfirmed against a live instance** (see verification note above) — if this metric isn't actually exported, fall back to `kafka_cluster_partition_insyncreplicascount{cluster="<name>"} < <topic's min.insync.replicas from describeTopicConfig>`. |
| `kafka_cluster_partition_insyncreplicascount` | `cluster`, `topic`, `partition` | Current ISR size. Compare against `replicascount` to find degraded partitions: `kafka_cluster_partition_insyncreplicascount{cluster="<name>"} < kafka_cluster_partition_replicascount{cluster="<name>"}` |
| `kafka_cluster_partition_firstfetchfromleader` | `cluster`, `topic`, `partition` | Milliseconds elapsed since a follower first fetched from the leader. This is non-zero for EVERY follower that has ever fetched — i.e. every healthy partition — so `> 0` is not a fault signal and will fire cluster-wide on a fully healthy cluster; it is not a query to run at all without a large threshold (e.g. `> 30000` for "hasn't fetched in 30s"), and even then it's a drill-down, not a triage step. Prefer the ISR-based signals (`underminisr`/`insyncreplicascount` above) to detect an actually-lagging replica. |

### Partition Info — Baseline Reference

Use these as reference data to interpret health metrics above, not as error signals themselves.

| Metric | Key labels | Description |
|---|---|---|
| `kafka_cluster_partition_replicascount` | `cluster`, `topic`, `partition` | Configured replication factor for this partition. Reference value for ISR comparison — does not change unless the topic is reconfigured. |
