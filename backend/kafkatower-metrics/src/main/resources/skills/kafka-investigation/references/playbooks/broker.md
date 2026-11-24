# Playbook — Broker

Input: broker ID.

### 1 — Admin: config
`describeBrokerConfigs(cluster, brokerId)`. Check `log.dirs`, `log.retention.ms`, `log.segment.bytes`,
`num.replica.fetchers`.

### 2 — Metrics: independent signals, one batch same turn
All need only `cluster` and `broker_id`. None depends on another's result.
```promql
kminion_kafka_broker_info{cluster="<name>", broker_id="<id>"}                                   # visible to KMinion
kafka_server_kafkarequesthandlerpool_requesthandleravgidlepercent{cluster="<name>", broker="<id>"}
kminion_kafka_received_bytes{cluster="<name>", broker_id="<id>"}
kminion_kafka_sent_bytes{cluster="<name>", broker_id="<id>"}
rate(kminion_kafka_requests_received_total{cluster="<name>", broker_id="<id>"}[5m])
kminion_kafka_broker_log_dir_size_total_bytes{cluster="<name>", broker_id="<id>"}
kafka_server_replicamanager_underreplicatedpartitions{cluster="<name>", broker="<id>"}          # under-replicated as seen by this broker
```
Per-partition metrics (`kafka_cluster_partition_*`) have no broker label. Broker involvement in
under-replication / ISR shrink: find affected topics cluster-wide
(`kafka_cluster_partition_underreplicated{cluster="<name>"} > 0`), then `describeTopic` for
leaders/replicas. Only dependency here, and only after the batch.
