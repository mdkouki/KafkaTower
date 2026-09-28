# Investigation Playbook — Consumer Group

**Input needed**: consumer group ID.

**If you don't already have one specific group ID** — because the question is about consumer
group health/lag across the whole cluster rather than a group the user named — do NOT run this
playbook once per group returned by `listConsumerGroups`. There is no bulk "describe" for
consumer groups; the only way to narrow a large population is metrics:
`topk(5, sum by (group_id) (kminion_kafka_consumer_group_topic_lag{cluster="<name>"}))` (or the
`consumer-group` scope's own lag query in Step 3 below, run once cluster-wide) to find the
worst-N groups. Only then come back to this playbook for those specific groups.

### Step 1 — Admin: group state, membership, and offsets — fire together

Neither call depends on the other's result, so issue both in the same turn:

```
describeConsumerGroup(cluster, groupId)
listConsumerGroupOffsets(cluster, groupId)
```

From `describeConsumerGroup`, record:
- **State**: `Stable` (healthy), `PreparingRebalance` / `CompletingRebalance` (rebalancing),
  `Empty` (no members), `Dead` (coordinator issue)
- Member count, client IDs, host assignments, partition assignments

From `listConsumerGroupOffsets`, record:
- Total lag as reported by admin
- Any partitions with null / never-committed offsets
- The partition with the highest lag (topic, partition, lag value)

> If state is not Stable, lag readings from `listConsumerGroupOffsets` are unreliable — note
> the state and weight the metrics interpretation below accordingly, but you still needed both
> calls up front regardless of state.

### Step 2 — Interpret admin findings before querying metrics

| Admin finding | What metrics to query |
|---|---|
| State = Stable, lag = 0 | No metrics needed — group is healthy |
| State = Stable, lag > 0 but stable | `kminion_kafka_consumer_group_topic_lag` 1h trend |
| State = Stable, lag growing | Lag rate + per-topic breakdown |
| State = Rebalancing | `kminion_kafka_consumer_group_info` state timeline |
| Never-committed partitions | `kminion_kafka_consumer_group_topic_partition_lag` history |
| Members missing | `kminion_kafka_consumer_group_members` trend |

### Step 3 — Metrics: pick per Step 2's table, batch whichever you need

Every block below is independent of every other — none of these queries' parameters depend on
another block's result — so once you know which block(s) Step 2 calls for, issue all of them
together in one turn rather than one block per turn.

```promql
# Group state and membership
kminion_kafka_consumer_group_info{cluster="<name>", group_id="<id>"}
kminion_kafka_consumer_group_members{cluster="<name>", group_id="<id>"}
kminion_kafka_consumer_group_empty_members{cluster="<name>", group_id="<id>"}

# Lag trend — total, per-topic, growth slope, worst partitions
sum(kminion_kafka_consumer_group_topic_lag{cluster="<name>", group_id="<id>"}) by (group_id)
kminion_kafka_consumer_group_topic_lag{cluster="<name>", group_id="<id>"}
# lag is a GAUGE, not a counter — rate() is wrong here and `[5m]` cannot attach to sum(...)
# directly, it needs subquery syntax. deriv()/delta() over a subquery is the correct pattern.
deriv(sum(kminion_kafka_consumer_group_topic_lag{cluster="<name>", group_id="<id>"})[15m:1m])
sort_desc(kminion_kafka_consumer_group_topic_partition_lag{cluster="<name>", group_id="<id>"})

# Offset commit health
kminion_kafka_consumer_group_topic_offset_sum{cluster="<name>", group_id="<id>"}
rate(kminion_kafka_consumer_group_offset_commits_total{cluster="<name>", group_id="<id>"}[5m])
kminion_kafka_consumer_group_topic_assigned_partitions{cluster="<name>", group_id="<id>"}
```

> `partition_id` is a **string** label. Use `partition_id="0"` not `partition="0"`.

### Consumer Group Checklist

- [ ] State is Stable (from admin)
- [ ] No never-committed partitions (from admin)
- [ ] `sum(kminion_kafka_consumer_group_topic_lag{cluster="<name>", group_id})` is bounded and not growing
- [ ] Member count matches expected (from admin + kminion trend)
- [ ] Offset commit rate is non-zero (from kminion offset query)

For the full KMinion consumer-group metric reference and more ready-to-use patterns, see the
`kminion` metrics catalog category.
