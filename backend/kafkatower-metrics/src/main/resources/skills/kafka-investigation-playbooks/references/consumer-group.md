# Playbook — Consumer Group

Input: one consumer group ID.

No specific group named (question about group health/lag across cluster)? Do not run this per ID
from `listConsumerGroups`. No bulk describe. Narrow with metrics once cluster-wide
(`topk(5, sum by (group_id) (kminion_kafka_consumer_group_topic_lag{cluster="<name>"}))`) for
worst-N groups, then run this for those.

### 1 — Admin: state, membership, offsets, both same turn
`describeConsumerGroup(cluster, groupId)` + `listConsumerGroupOffsets(cluster, groupId)`.
- Describe: state (`Stable`, `PreparingRebalance`/`CompletingRebalance`, `Empty`, `Dead`), member
  count, client IDs, hosts, partition assignments.
- Offsets: total lag, never-committed partitions, worst-lag partition (topic, partition, lag).

State not Stable → admin lag unreliable. Note state, weight metrics accordingly.

### 2 — Interpret before metrics
| Admin finding | Metrics |
|---|---|
| Stable, lag 0 | none, healthy |
| Stable, lag > 0 steady | `kminion_kafka_consumer_group_topic_lag` 1h trend |
| Stable, lag growing | lag slope + per-topic breakdown |
| Rebalancing | `kminion_kafka_consumer_group_info` state timeline |
| Never-committed partitions | `kminion_kafka_consumer_group_topic_partition_lag` history |
| Members missing | `kminion_kafka_consumer_group_members` trend |

### 3 — Metrics: pick per Step 2, batch all you need in one turn
Blocks independent.
```promql
# state, membership
kminion_kafka_consumer_group_info{cluster="<name>", group_id="<id>"}
kminion_kafka_consumer_group_members{cluster="<name>", group_id="<id>"}
kminion_kafka_consumer_group_empty_members{cluster="<name>", group_id="<id>"}
# lag: total, per topic, slope, worst partitions
sum(kminion_kafka_consumer_group_topic_lag{cluster="<name>", group_id="<id>"}) by (group_id)
kminion_kafka_consumer_group_topic_lag{cluster="<name>", group_id="<id>"}
deriv(sum(kminion_kafka_consumer_group_topic_lag{cluster="<name>", group_id="<id>"})[15m:1m])
sort_desc(kminion_kafka_consumer_group_topic_partition_lag{cluster="<name>", group_id="<id>"})
# offset commit health
kminion_kafka_consumer_group_topic_offset_sum{cluster="<name>", group_id="<id>"}
rate(kminion_kafka_consumer_group_offset_commits_total{cluster="<name>", group_id="<id>"}[5m])
kminion_kafka_consumer_group_topic_assigned_partitions{cluster="<name>", group_id="<id>"}
```
Lag = gauge, not counter. `rate()` wrong. `[15m]` cannot attach to `sum(...)`: subquery
`[15m:1m]`, `deriv()`/`delta()`. `partition_id` is string: `partition_id="0"`.

### Checklist
- [ ] State Stable (admin)
- [ ] No never-committed partitions (admin)
- [ ] Total lag bounded, not growing
- [ ] Member count as expected (admin + kminion trend)
- [ ] Offset commit rate non-zero

More ready patterns: `kminion` catalog.
