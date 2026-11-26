---
name: kafka-metrics-catalog
description: "Use this skill to look up the exact name, labels and meaning of a Kafka metric (kminion_* and kafka_*) before querying it. Read it before using any name that discovery did not confirm this turn, even if you think you remember it."
---

# Kafka Metrics Catalog

Never use a metric name that is neither listed here nor returned by `metrics` discovery. Read only
the reference the question needs.

| Reference | Read when |
|---|---|
| `references/kminion.md` | Consumer group lag/state/offsets, topic size/HWM, broker info/disk/traffic, cluster info. Most common, live-verified: start here. |
| `references/kafka-core.md` | Error rate (the primary error signal), controller state, replica manager, partition/ISR topology. Any health or error investigation. |
| `references/kafka-extended.md` | Consumer client-side, group coordinator, request handler saturation, per-user quotas, per-topic broker usage. Rare: only if the other two lack the name. |
