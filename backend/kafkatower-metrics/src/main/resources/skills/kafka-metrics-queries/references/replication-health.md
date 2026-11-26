# Partition and Replication Health

Run after triage shows under-replicated or offline partitions > 0, or when the question is about ISR, replication or `min.insync.replicas`.

```promql
kafka_cluster_partition_underreplicated{cluster="<name>"} > 0     # which partitions
kafka_cluster_partition_insyncreplicascount{cluster="<name>"} < kafka_cluster_partition_replicascount{cluster="<name>"}   # ISR shrunk = under-replicated
kafka_cluster_partition_atminisr{cluster="<name>"} > 0            # ISR == min.insync: "at risk", NOT writes failing; normal during one-broker maintenance
kafka_cluster_partition_underminisr{cluster="<name>"} > 0         # ISR below min.insync: acks=all writes failing. Unconfirmed
```
`underminisr` empty/absent → not exported. Below-min-ISR then needs topic's `min.insync.replicas`
(not available here). Say unconfirmed. Do not use ISR < replicas as stand-in: that is
under-replicated, not write failure. Never use `kafka_cluster_partition_firstfetchfromleader > 0`
(non-zero on every healthy follower; see catalog).
