---
name: kafka-metrics-inspector
description: "Use this skill to answer historical time-series questions about a Kafka cluster from the metrics store: consumer lag and trends, throughput, replication health, broker saturation, capacity. Covers metric discovery, hard query constraints and the standard triage and lag queries."
---

# Kafka Metrics Inspector

Historical time-series from the metrics store. Not live broker state.

## Source of truth
Metric names come only from the `metrics` discovery result or the `kafka-metrics-catalog` skill.
Never guess or invent a name. Read the catalog's matching reference before using a name not
confirmed this turn.

## Hard constraints
- Every query has `cluster="<name>"`. Never without.
- Only `kafka_` and `kminion_` metrics.
- No raw per-partition / per-group series for a general question. Aggregate: `topk`, `sum by`, `avg by`.
- Max 20 series per response. More → tighter filter, or `bulkOperation` on the result key
  (`sort`, `filter_number`, `group_by`).
- Default `query` (instant). `query_range` only if the user asks for trend/history (last 1h step 10m,
  last 24h step 30m).
- `by()` only labels the answer needs. No partition/pod/uid/hash unless the question targets one.

## Workflow
A catalog of what to check, not a fixed script. Choose your own path (Query Strategy).

### Step 0 — Discovery (first, once)
`metrics(match)`. `match` = plain Java regex on metric names, case-insensitive. NOT a PromQL
selector: `{__name__=~"..."}` throws "Illegal repetition". Narrowest row:

| Question | `match` |
|---|---|
| Consumer lag, group state/health | `kminion_kafka_consumer_group` |
| Broker traffic / IO | `kminion_kafka` |
| Partition replication, ISR | `kafka_cluster_partition` |
| Request errors, protocol failures | `kafka_network_request_metrics` |
| Request handler saturation | `kafka_server_kafkarequesthandlerpool` |

General health, or no row fits: `^(kminion|kafka)_` as-is (`|` = alternation; write it plain, no
backslash). No hand-built combos: top-level `kminion_*` (e.g. `kminion_exporter_up`) falls outside
`kminion_kafka`.

### Step 1 — Triage (cluster-wide, first)
```promql
sum(kafka_server_replicamanager_underreplicatedpartitions{cluster="<name>"})   # >0 replication lag
kafka_controller_kafkacontroller_offlinepartitionscount{cluster="<name>"}      # >0 critical, unavailable
sum(rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error!="NONE"}[5m]))   # any errors?
sum by (request) (rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error!="NONE"}[5m]))
sum by (error) (rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error!="NONE"}[5m]))
sum by (broker) (rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error!="NONE"}[5m]))
```
Replication or offline > 0 → check partition health before consumer lag. Errors zero → skip the
three `by` breakdowns.

### Step 2 — Consumer lag
| Prompt | Query |
|---|---|
| General ("top lagging groups") | `topk(5, sum by (group_id, topic) (kminion_kafka_consumer_group_topic_lag{cluster="<name>"}))` |
| Group | `sum by (group_id, topic) (kminion_kafka_consumer_group_topic_lag{cluster="<name>", group_id="<group>"})` |
| Topic | `sum by (group_id, topic) (kminion_kafka_consumer_group_topic_lag{cluster="<name>", topic="<topic>"})` |
| Partition (only if asked) | `kminion_kafka_consumer_group_topic_partition_lag{cluster="<name>", group_id="<group>", partition_id="<n>"}` |

State: `kminion_kafka_consumer_group_info{cluster="<name>", group_id="<group>"}`,
`kminion_kafka_consumer_group_members{cluster="<name>", group_id="<group>"}`.

## Reference skills
Read only when the trigger applies; skip otherwise.

| Skill: reference | Read when |
|---|---|
| `kafka-metrics-queries`: `replication-health.md` | Triage shows under-replicated or offline partitions > 0, or the question is about ISR, replication or `min.insync.replicas`. |
| `kafka-metrics-queries`: `broker-saturation.md` | The question is about request handler saturation, broker availability or an overloaded broker. |
| `kafka-metrics-queries`: `storage-and-traffic.md` | The question is about disk, capacity or log size, or network and request throughput. |
| `kafka-metrics-catalog`: `kminion.md`, `kafka-core.md`, `kafka-extended.md` | A metric name is not covered above and discovery did not confirm it. |
