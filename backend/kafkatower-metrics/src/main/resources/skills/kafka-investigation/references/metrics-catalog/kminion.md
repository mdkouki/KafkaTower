# Kafka Metrics Catalog — KMinion

All metric names confirmed available in this VictoriaMetrics environment. `kminion_*` entries
are live-verified. (Cluster-label and no-guessing-names rules are in your system prompt, not
repeated per category here.)

---

## KMinion — Exporter Health

| Metric | Key labels | Description |
|---|---|---|
| `kminion_exporter_up` | `cluster` | 1 if KMinion is running and connected to Kafka |
| `kminion_exporter_offset_consumer_records_consumed_total` | `cluster` | Internal offset consumer records (counter) |
| `kminion_log_messages_total` | `cluster` | KMinion log messages by level |

---

## KMinion — Cluster

| Metric | Key labels | Description |
|---|---|---|
| `kminion_kafka_cluster_info` | `cluster`, `cluster_id`, `broker_count` | Cluster metadata gauge |

---

## KMinion — Broker

| Metric | Key labels | Description |
|---|---|---|
| `kminion_kafka_broker_info` | `cluster`, `broker_id`, `address`, `rack_id` | Broker metadata gauge |
| `kminion_kafka_broker_log_dir_size_total_bytes` | `cluster`, `broker_id` | Total log directory size on this broker |

---

## KMinion — Topic

| Metric | Key labels | Description |
|---|---|---|
| `kminion_kafka_topic_info` | `cluster`, `topic`, `partition_count`, `replication_factor` | Topic metadata gauge |
| `kminion_kafka_topic_log_dir_size_total_bytes` | `cluster`, `topic` | Total log size for this topic |
| `kminion_kafka_topic_high_water_mark_sum` | `cluster`, `topic` | Sum of high water marks across all partitions |
| `kminion_kafka_topic_low_water_mark_sum` | `cluster`, `topic` | Sum of low water marks across all partitions |
| `kminion_kafka_topic_partition_high_water_mark` | `cluster`, `topic`, `partition_id` | Per-partition high water mark |
| `kminion_kafka_topic_partition_low_water_mark` | `cluster`, `topic`, `partition_id` | Per-partition low water mark |

---

## KMinion — Consumer Group (group level)

| Metric | Key labels | Description |
|---|---|---|
| `kminion_kafka_consumer_group_info` | `cluster`, `group_id`, `protocol_type`, `state` | Group metadata and current state |
| `kminion_kafka_consumer_group_members` | `cluster`, `group_id` | Total active member count |
| `kminion_kafka_consumer_group_empty_members` | `cluster`, `group_id` | Members with no partition assignments |

---

## KMinion — Consumer Group (topic level)

| Metric | Key labels | Description |
|---|---|---|
| `kminion_kafka_consumer_group_offset_commits_total` | `cluster`, `group_id`, `topic` | Total offset commits (counter) |
| `kminion_kafka_consumer_group_topic_assigned_partitions` | `cluster`, `group_id`, `topic` | Partitions assigned to this group on this topic |
| `kminion_kafka_consumer_group_topic_members` | `cluster`, `group_id`, `topic` | Members consuming from this topic |
| `kminion_kafka_consumer_group_topic_offset_sum` | `cluster`, `group_id`, `topic` | Sum of committed offsets |
| `kminion_kafka_consumer_group_topic_lag` | `cluster`, `group_id`, `topic` | Total lag (LEO − committed) for this group × topic |

---

## KMinion — Consumer Group (partition level)

| Metric | Key labels | Description |
|---|---|---|
| `kminion_kafka_consumer_group_topic_partition_lag` | `cluster`, `group_id`, `topic`, `partition_id` | Lag for one partition. `partition_id` is a **string** label — filter as `partition_id="0"` |

---

## KMinion — Traffic / I/O

| Metric | Key labels | Description |
|---|---|---|
| `kminion_kafka_received_bytes` | `cluster`, `broker_id` | Bytes received by broker |
| `kminion_kafka_sent_bytes` | `cluster`, `broker_id` | Bytes sent by broker |
| `kminion_kafka_requests_received_total` | `cluster`, `broker_id` | Requests received (counter) |
| `kminion_kafka_requests_sent_total` | `cluster`, `broker_id` | Requests sent (counter) |

---

## KMinion Consumer Group Metrics — Quick Reference

All consumer group metrics use the `kminion_kafka_consumer_group_*` prefix.

| Metric | Granularity | Key labels |
|---|---|---|
| `kminion_kafka_consumer_group_info` | group | `group_id`, `state` |
| `kminion_kafka_consumer_group_members` | group | `group_id` |
| `kminion_kafka_consumer_group_empty_members` | group | `group_id` |
| `kminion_kafka_consumer_group_offset_commits_total` | group × topic | `group_id`, `topic` |
| `kminion_kafka_consumer_group_topic_assigned_partitions` | group × topic | `group_id`, `topic` |
| `kminion_kafka_consumer_group_topic_members` | group × topic | `group_id`, `topic` |
| `kminion_kafka_consumer_group_topic_offset_sum` | group × topic | `group_id`, `topic` |
| `kminion_kafka_consumer_group_topic_lag` | group × topic | `group_id`, `topic` |
| `kminion_kafka_consumer_group_topic_partition_lag` | per partition | `group_id`, `topic`, `partition_id` (string) |

**State label values in `kminion_kafka_consumer_group_info`**:
`Stable`, `Empty`, `PreparingRebalance`, `CompletingRebalance`, `Dead`

### Ready-to-use patterns

```promql
# Total lag — single group (sum across topics)
sum(kminion_kafka_consumer_group_topic_lag{cluster="<name>", group_id="<id>"}) by (group_id)

# Per-topic lag breakdown
kminion_kafka_consumer_group_topic_lag{cluster="<name>", group_id="<id>"}

# Worst partitions
sort_desc(kminion_kafka_consumer_group_topic_partition_lag{cluster="<name>", group_id="<id>"})

# Top 10 lagging groups cluster-wide
topk(10, sum(kminion_kafka_consumer_group_topic_lag{cluster="<name>"}) by (group_id))

# Is lag growing? (positive slope = falling behind; gauge — use deriv()/delta() over a
# subquery, not rate())
deriv(sum(kminion_kafka_consumer_group_topic_lag{cluster="<name>", group_id="<id>"})[15m:1m])

# Groups currently rebalancing
kminion_kafka_consumer_group_info{cluster="<name>", state=~"PreparingRebalance|CompletingRebalance"}

# Empty groups (no active members)
kminion_kafka_consumer_group_info{cluster="<name>", state="Empty"}

# Groups with zero members
kminion_kafka_consumer_group_members{cluster="<name>"} == 0

# Top lagging groups on a specific topic
topk(5, kminion_kafka_consumer_group_topic_lag{cluster="<name>", topic="<name>"})

# Committed offset advancing? (flat = consumer stopped)
rate(kminion_kafka_consumer_group_topic_offset_sum{cluster="<name>", group_id="<id>"}[5m])
```
