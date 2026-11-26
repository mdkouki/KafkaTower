---
name: kafka-investigation-playbooks
description: "Use this skill when investigating a specific Kafka cluster, broker, topic, consumer group or principal, or a symptom such as 'errors spiking': it gives the admin calls in order, the metrics to check afterwards and a completion checklist."
---

# Kafka Investigation Playbooks

Read the one playbook that matches the scope; most questions need exactly one.

| Reference | Read when |
|---|---|
| `references/cluster.md` | Cluster-wide health: broker count, error rate, replication, traffic. Default when the scope is ambiguous. |
| `references/broker.md` | A specific broker: config, disk, replication load, saturation, traffic. |
| `references/topic.md` | A specific topic: metadata, config, size, replication health, consumer lag. |
| `references/consumer-group.md` | A specific consumer group: state, membership, lag, offset commits. |
| `references/principal.md` | ACL or auth problems for a specific principal: ACLs, quotas, auth failures. |
| `references/failure-patterns.md` | A symptom with no named resource ("errors spiking", "lag growing"). |

A playbook names the metrics to check; confirm their exact names and labels in the
`kafka-metrics-catalog` skill before building PromQL.
