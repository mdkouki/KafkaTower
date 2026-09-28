# Investigation Playbook — Topic

**Input needed**: topic name.

### Step 1 — Admin: topic metadata and configuration — fire together

Neither call depends on the other's result, so issue both in the same turn:

```
describeTopic(cluster, topic)
describeTopicConfigs(cluster, topic)
```

From `describeTopic`, record: partition count, replication factor, leader for each partition,
ISR sets. Flag any partition where `ISR size < replication factor`.
From `describeTopicConfigs`, note: `retention.ms`, `retention.bytes`, `cleanup.policy`,
`min.insync.replicas`. Confirm `min.insync.replicas <= replication.factor - 1`.

### Step 2 — Interpret admin findings before querying metrics

| Admin finding | Metrics to query |
|---|---|
| ISR < RF on any partition | `kafka_cluster_partition_underreplicated{cluster="<name>", topic="<name>"}` |
| Retention config looks wrong | No metric needed — config already retrieved |
| Partition count is high | kminion topic HWM growth rate as production proxy |
| Uneven leader distribution | `kafka_cluster_partition_insyncreplicascount` per partition |

The question itself may also call for metrics this table doesn't cover (e.g. "how much lag is
this topic causing" needs the consumer-lag block below regardless of what admin found).

### Step 3 — Metrics: pick per Step 2's table, batch whichever you need

Every block below is independent of every other — none of these queries' parameters depend on
another block's result — so once you know which block(s) the question and Step 2 call for,
issue all of them together in one turn rather than one block per turn.

```promql
# Topic info and size
kminion_kafka_topic_info{cluster="<name>", topic="<name>"}
kminion_kafka_topic_log_dir_size_total_bytes{cluster="<name>", topic="<name>"}

# Production rate (HWM growth as proxy)
rate(kminion_kafka_topic_high_water_mark_sum{cluster="<name>", topic="<name>"}[5m])
kminion_kafka_topic_partition_high_water_mark{cluster="<name>", topic="<name>"}

# Replication health for this topic
kafka_cluster_partition_underreplicated{cluster="<name>", topic="<name>"}
kafka_cluster_partition_atminisr{cluster="<name>", topic="<name>"}
kafka_cluster_partition_insyncreplicascount{cluster="<name>", topic="<name>"}

# Consumer group lag on this topic
topk(5, kminion_kafka_consumer_group_topic_lag{cluster="<name>", topic="<name>"})
topk(5, rate(kminion_kafka_consumer_group_topic_lag{cluster="<name>", topic="<name>"}[5m]))
```

### Topic Checklist

- [ ] ISR == replication factor for all partitions (from admin)
- [ ] min.insync.replicas is sane relative to replication factor (from admin)
- [ ] Retention config matches expectations (from admin)
- [ ] `kafka_cluster_partition_underreplicated{cluster="<name>", topic} == 0`
- [ ] No consumer group lag growing on this topic
