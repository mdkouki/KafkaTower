# Catalog — Core Health (`kafka_*`)

Cluster-label and no-guessing rules are in your skill instructions.

> **Provisional.** These JMX-exporter names are NOT verified on a live instance: dev metrics
> backend exposes zero `kafka_*` series. Confirm with `label_values(__name__)` filtered on `kafka_`
> against target store before relying on a name.

## Error rate. PRIMARY error signal, query first
| Metric | Labels | Meaning |
|---|---|---|
| `kafka_network_request_metrics_errorpersec` | `cluster`, `request`, `error`, `broker` | per-second errors by request type and error code. `error!="NONE"` drops successes. |

Real-time protocol failures across all requests and brokers. Go to partition topology metrics only
after rate is non-zero and you must locate topics/partitions. Query sequence: cluster playbook Step 4.

## Controller
| Metric | Labels | Meaning |
|---|---|---|
| `kafka_controller_kafkacontroller_activecontrollercount` | `cluster` | active controllers, controller's view |
| `kafka_controller_kafkacontroller_activebrokercount` | `cluster` | active brokers, controller's view |
| `kafka_controller_kafkacontroller_offlinepartitionscount` | `cluster` | partitions with no online leader. >0 = unavailable for read/write, critical |

Other `kafka_controller_*` exist (election rates, partition counts).

## Replica manager. Cheap triage
| Metric | Labels | Meaning |
|---|---|---|
| `kafka_server_replicamanager_underreplicatedpartitions` | `cluster`, `broker` | under-replicated partitions on this broker. `sum()` for cluster count. >0 = replication lag. Start here, not per-partition. |

## Cluster partition (`kafka_cluster_partition_*`)
One series per partition: high cardinality. Use after triage confirms non-zero, to find *which*
partitions. Filter `> 0` or `count() by (topic)`.

| Metric | Labels | Meaning |
|---|---|---|
| `kafka_cluster_partition_underreplicated` | `cluster`, `topic`, `partition` | 1 = under-replicated. `{cluster="<name>"} > 0` |
| `kafka_cluster_partition_atminisr` | `cluster`, `topic`, `partition` | 1 = ISR exactly `min.insync.replicas`: last loss `acks=all` can absorb. "At risk", NOT failing: healthy RF=3/min.isr=2 sits at 1 while one broker down for maintenance |
| `kafka_cluster_partition_underminisr` | `cluster`, `topic`, `partition` | 1 = ISR BELOW `min.insync.replicas`: `acks=all` producers get `NOT_ENOUGH_REPLICAS`. The "writes failing" signal. **Unconfirmed.** Not exported → compare `insyncreplicascount` with topic's `min.insync.replicas` (`describeTopicConfigs`) |
| `kafka_cluster_partition_insyncreplicascount` | `cluster`, `topic`, `partition` | ISR size. `< replicascount` = under-replicated: `kafka_cluster_partition_insyncreplicascount{cluster="<name>"} < kafka_cluster_partition_replicascount{cluster="<name>"}` |
| `kafka_cluster_partition_replicascount` | `cluster`, `topic`, `partition` | configured RF. Reference for ISR comparison |
| `kafka_cluster_partition_firstfetchfromleader` | `cluster`, `topic`, `partition` | ms since follower first fetched. Non-zero for EVERY follower that ever fetched = every healthy partition. `> 0` is NOT a fault signal. Only with large threshold (`> 30000`) as drill-down. Prefer ISR signals. |
