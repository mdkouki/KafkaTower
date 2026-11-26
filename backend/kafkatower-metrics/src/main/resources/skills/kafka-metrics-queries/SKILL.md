---
name: kafka-metrics-queries
description: "Use this skill for metrics questions beyond triage and consumer lag: partition and replication (ISR) health, broker saturation and availability, storage and capacity, network and request traffic. Gives ready-made PromQL for each."
---

# Kafka Metrics Queries

Read only the reference the question needs.

| Reference | Read when |
|---|---|
| `references/replication-health.md` | Triage shows under-replicated or offline partitions > 0, or the question is about ISR, replication or `min.insync.replicas`. |
| `references/broker-saturation.md` | The question is about request handler saturation, broker availability or an overloaded broker. |
| `references/storage-and-traffic.md` | The question is about disk, capacity or log size, or network and request throughput. |
