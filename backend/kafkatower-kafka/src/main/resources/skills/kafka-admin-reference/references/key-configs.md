# Kafka Admin Reference — Key Configs

Read-only. Tool names and parameters are in the tool list. This holds only what tools do not say.

| Config | Healthy | Problem if |
|---|---|---|
| `min.insync.replicas` (topic) | RF - 1 | = RF: one broker down = unproducible |
| `unclean.leader.election.enable` | false | true: data loss risk |
| `log.retention.ms` | per SLA | unexpectedly short: data loss |
| `max.poll.interval.ms` (consumer) | | < processing time: rebalance loops |
| `session.timeout.ms` (consumer) | | < GC pause: false evictions |
