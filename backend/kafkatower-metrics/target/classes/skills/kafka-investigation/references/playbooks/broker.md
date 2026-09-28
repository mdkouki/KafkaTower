# Investigation Playbook — Broker

**Input needed**: broker ID.

### Step 1 — Admin: broker configuration

```
describeBrokerConfigs(cluster, brokerId)
```
Check: `log.dirs`, `log.retention.ms`, `log.segment.bytes`, `num.replica.fetchers`.

### Step 2 — Metrics: independent signals for this broker — fire together, not one at a time

Every query below takes only `cluster` and `broker_id`, both already known from this
playbook's input — none of them depends on another's result, so issue them as one batch of
tool calls in the same turn rather than waiting for each result before sending the next.    

```promql
# Broker identity (confirms broker is visible to KMinion)
kminion_kafka_broker_info{cluster="<name>", broker_id="<id>"}

# Partitions under-replicated where this broker is involved
kafka_cluster_partition_underreplicated{cluster="<name>", topic=~".+"}

# ISR shrinkage — partitions where ISR < replica count
kafka_cluster_partition_insyncreplicascount{cluster="<name>"} < kafka_cluster_partition_replicascount{cluster="<name>"}

# Request handler idle ratio for this broker
kafka_server_kafkarequesthandlerpool_requesthandleravgidlepercent{cluster="<name>", broker="<id>"}

# Bytes in/out
kminion_kafka_received_bytes{cluster="<name>", broker_id="<id>"}
kminion_kafka_sent_bytes{cluster="<name>", broker_id="<id>"}

# Request rate
rate(kminion_kafka_requests_received_total{cluster="<name>", broker_id="<id>"}[5m])

# Log directory size for this broker
kminion_kafka_broker_log_dir_size_total_bytes{cluster="<name>", broker_id="<id>"}
```

If the under-replicated/ISR-shrinkage queries above surface affected topics, filter a
follow-up query by that topic/partition — that's the one genuine dependency here, and it only
applies after you've seen this batch's results, not before.