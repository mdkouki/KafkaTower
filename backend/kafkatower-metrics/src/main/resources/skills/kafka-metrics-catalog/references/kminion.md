# Catalog — KMinion (`kminion_*`)

Live-verified names. Cluster-label and no-guessing rules are in your skill instructions.

## Exporter
| Metric | Labels | Meaning |
|---|---|---|
| `kminion_exporter_up` | `cluster` | 1 = KMinion running and connected to Kafka |
| `kminion_exporter_offset_consumer_records_consumed_total` | `cluster` | internal offset consumer records (counter) |
| `kminion_log_messages_total` | `cluster` | KMinion log messages by level |

## Cluster, broker
| Metric | Labels | Meaning |
|---|---|---|
| `kminion_kafka_cluster_info` | `cluster`, `cluster_id`, `broker_count` | cluster metadata gauge |
| `kminion_kafka_broker_info` | `cluster`, `broker_id`, `address`, `rack_id` | broker metadata gauge |
| `kminion_kafka_broker_log_dir_size_total_bytes` | `cluster`, `broker_id` | log dir size on broker |

## Topic
| Metric | Labels | Meaning |
|---|---|---|
| `kminion_kafka_topic_info` | `cluster`, `topic`, `partition_count`, `replication_factor` | topic metadata gauge |
| `kminion_kafka_topic_log_dir_size_total_bytes` | `cluster`, `topic` | topic log size |
| `kminion_kafka_topic_high_water_mark_sum` | `cluster`, `topic` | sum of HWM over partitions |
| `kminion_kafka_topic_low_water_mark_sum` | `cluster`, `topic` | sum of low watermarks over partitions |
| `kminion_kafka_topic_partition_high_water_mark` | `cluster`, `topic`, `partition_id` | per-partition HWM |
| `kminion_kafka_topic_partition_low_water_mark` | `cluster`, `topic`, `partition_id` | per-partition low watermark |

## Consumer group
`partition_id` is a **string** label: `partition_id="0"`. Lag metrics are gauges.
| Metric | Labels | Meaning |
|---|---|---|
| `kminion_kafka_consumer_group_info` | `cluster`, `group_id`, `protocol_type`, `state` | group metadata + state |
| `kminion_kafka_consumer_group_members` | `cluster`, `group_id` | active members |
| `kminion_kafka_consumer_group_empty_members` | `cluster`, `group_id` | members with no assignment |
| `kminion_kafka_consumer_group_offset_commits_total` | `cluster`, `group_id`, `topic` | offset commits (counter) |
| `kminion_kafka_consumer_group_topic_assigned_partitions` | `cluster`, `group_id`, `topic` | partitions assigned on topic |
| `kminion_kafka_consumer_group_topic_members` | `cluster`, `group_id`, `topic` | members consuming topic |
| `kminion_kafka_consumer_group_topic_offset_sum` | `cluster`, `group_id`, `topic` | sum of committed offsets |
| `kminion_kafka_consumer_group_topic_lag` | `cluster`, `group_id`, `topic` | total lag (LEO − committed), group × topic |
| `kminion_kafka_consumer_group_topic_partition_lag` | `cluster`, `group_id`, `topic`, `partition_id` | lag of one partition |

`state` values: `Stable`, `Empty`, `PreparingRebalance`, `CompletingRebalance`, `Dead`.

## Traffic
| Metric | Labels | Meaning |
|---|---|---|
| `kminion_kafka_received_bytes` | `cluster`, `broker_id` | bytes received by broker |
| `kminion_kafka_sent_bytes` | `cluster`, `broker_id` | bytes sent by broker |
| `kminion_kafka_requests_received_total` | `cluster`, `broker_id` | requests received (counter) |
| `kminion_kafka_requests_sent_total` | `cluster`, `broker_id` | requests sent (counter) |

## Ready patterns
```promql
sum(kminion_kafka_consumer_group_topic_lag{cluster="<name>", group_id="<id>"}) by (group_id)        # total lag, one group
topk(10, sum(kminion_kafka_consumer_group_topic_lag{cluster="<name>"}) by (group_id))               # top lagging groups
topk(5, kminion_kafka_consumer_group_topic_lag{cluster="<name>", topic="<name>"})                   # top lagging on topic
sort_desc(kminion_kafka_consumer_group_topic_partition_lag{cluster="<name>", group_id="<id>"})      # worst partitions
deriv(sum(kminion_kafka_consumer_group_topic_lag{cluster="<name>", group_id="<id>"})[15m:1m])       # growing? >0 = falling behind. Gauge: deriv/delta over subquery, not rate()
kminion_kafka_consumer_group_info{cluster="<name>", state=~"PreparingRebalance|CompletingRebalance"}   # rebalancing
kminion_kafka_consumer_group_info{cluster="<name>", state="Empty"}                                  # empty groups
kminion_kafka_consumer_group_members{cluster="<name>"} == 0                                         # zero members
rate(kminion_kafka_consumer_group_topic_offset_sum{cluster="<name>", group_id="<id>"}[5m])          # offsets advancing? flat = consumer stopped
```
