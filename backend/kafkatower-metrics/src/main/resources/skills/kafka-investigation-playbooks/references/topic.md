# Playbook — Topic

Input: topic name.

### 1 — Admin: metadata and config, both same turn
`describeTopic(cluster, topic)` + `describeTopicConfigs(cluster, topic)`.
- `describeTopic`: partition count, RF, leader per partition, ISR sets. Flag ISR size < RF.
- `describeTopicConfigs`: `retention.ms`, `retention.bytes`, `cleanup.policy`, `min.insync.replicas`.
  Confirm `min.insync.replicas <= RF - 1`.

### 2 — Interpret before metrics
| Admin finding | Metrics |
|---|---|
| ISR < RF on any partition | `kafka_cluster_partition_underreplicated{cluster="<name>", topic="<name>"}` |
| Retention config looks wrong | none, config already read |
| Partition count high | kminion topic HWM growth rate (production proxy) |
| Uneven leader distribution | `kafka_cluster_partition_insyncreplicascount` per partition |

Question may need metrics table misses (e.g. "how much lag does this topic cause" → lag block
below regardless of admin).

### 3 — Metrics: pick per Step 2, batch all you need in one turn
Blocks independent.
```promql
# size, info
kminion_kafka_topic_info{cluster="<name>", topic="<name>"}
kminion_kafka_topic_log_dir_size_total_bytes{cluster="<name>", topic="<name>"}
# production rate (HWM growth proxy)
rate(kminion_kafka_topic_high_water_mark_sum{cluster="<name>", topic="<name>"}[5m])
kminion_kafka_topic_partition_high_water_mark{cluster="<name>", topic="<name>"}
# replication health
kafka_cluster_partition_underreplicated{cluster="<name>", topic="<name>"}
kafka_cluster_partition_atminisr{cluster="<name>", topic="<name>"}
kafka_cluster_partition_insyncreplicascount{cluster="<name>", topic="<name>"}
# consumer lag on topic
topk(5, kminion_kafka_consumer_group_topic_lag{cluster="<name>", topic="<name>"})
topk(5, deriv(kminion_kafka_consumer_group_topic_lag{cluster="<name>", topic="<name>"}[15m]))
```
Lag is a gauge: `deriv()`, not `rate()`.

### Checklist
- [ ] ISR == RF all partitions (admin)
- [ ] `min.insync.replicas` sane vs RF (admin)
- [ ] Retention as expected (admin)
- [ ] Under-replicated for topic == 0
- [ ] No growing consumer lag on topic
