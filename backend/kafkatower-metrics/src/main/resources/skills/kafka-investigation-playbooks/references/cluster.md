# Playbook — Cluster

Goal: health snapshot before drilling into subsystems. Cluster/broker-level admin calls and
aggregate PromQL only (`sum`, `count`, no `by (topic)` unless a step says so). No `describeTopic`
per topic. `describeTopic` only for topic Step 5 flags or user named.

### 1 — Admin: overview
`describeCluster(cluster)`. Check broker count, controller ID, cluster ID. Missing brokers: note now.

### 2 — Admin: broker configs (sample one broker)
`describeBrokerConfigs(cluster, brokerId)`. Look at `num.io.threads`, `num.network.threads`,
`log.dirs`, `default.replication.factor`.

### 3 — Metrics: exporter and broker count
```promql
kminion_exporter_up{cluster="<name>"}                                         # KMinion up?
kafka_controller_kafkacontroller_activebrokercount{cluster="<name>"}          # compare with describeCluster
```

### 4 — Metrics: error rate. PRIMARY signal, before partition metrics
```promql
sum(rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error!="NONE"}[5m]))              # 4a any errors? zero → skip 4b-4d
sum by (request) (rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error!="NONE"}[5m]))  # 4b which requests
sum by (error) (rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error!="NONE"}[5m]))    # 4c which codes
sum by (broker) (rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error!="NONE"}[5m]))   # 4d which brokers
```
4a non-zero → record request types, codes, brokers, go Step 5.

### 5 — Metrics: replication health
```promql
sum(kafka_server_replicamanager_underreplicatedpartitions{cluster="<name>"})   # triage count; >0 degraded
kafka_cluster_partition_underreplicated{cluster="<name>"} > 0                  # only if above >0: which partitions
sum(kafka_cluster_partition_atminisr{cluster="<name>"})                        # at risk, not broken
sum(kafka_cluster_partition_underminisr{cluster="<name>"})                     # writes failing now. Unconfirmed
```
- Under-replicated > 0 → escalate. Fetch `topic` playbook for affected topics.
- `underminisr` > 0 → writes failing, escalate regardless of under-replicated count.
- `underminisr` absent → unconfirmed. Needs topic `min.insync.replicas` from `describeTopicConfigs`
  vs `kafka_cluster_partition_insyncreplicascount`. ISR < replicas is under-replicated, not write failure.

### 6 — Metrics: traffic
```promql
kminion_kafka_received_bytes{cluster="<name>"}
kminion_kafka_sent_bytes{cluster="<name>"}
rate(kminion_kafka_requests_received_total{cluster="<name>"}[5m])
```

### 7 — Metrics: handler saturation (if traffic abnormal)
`kafka_server_kafkarequesthandlerpool_requesthandleravgidlepercent{cluster="<name>"}`: <30% saturation, <10% critical.

### Checklist
- [ ] All expected brokers in `describeCluster`, one controller
- [ ] Error rate (4a) zero
- [ ] Under-replicated sum zero
- [ ] `underminisr` sum zero (non-zero = writes failing). `atminisr` occasionally non-zero is fine alone.
- [ ] Traffic normal, no spikes or drops
- [ ] Handler idle > 30%
