# Catalog — Extended (`kafka_*`, rare)

Consumer client-side, group coordinator, handler saturation, per-user quotas, per-topic broker
usage. Core catalog first. Cluster-label and no-guessing rules are in your skill instructions.

> **Provisional.** JMX-exporter names NOT verified on a live instance (dev backend exposes zero
> `kafka_*` series). Confirm with `label_values(__name__)` filtered on `kafka_` first.

## Consumer client-side (`kafka_consumer_*`)
Only if consumer JMX is scraped.
| Metric | Labels | Meaning |
|---|---|---|
| `kafka_consumer_consumer_coordinator_metrics_commit_total` | `cluster`, `client_id`, `group_id` | offset commits via coordinator |
| `kafka_consumer_fetch_manager_records_lag_max` | `cluster`, `client_id`, `topic`, `partition` | max fetch lag seen by consumer |

## Group coordinator (`kafka_coordinator_group_*`)
Broker-side join/sync/heartbeat/rebalance rates. Many metrics.

## Request handler (`kafka_server_kafkarequesthandlerpool_*`)
| Metric | Labels | Meaning |
|---|---|---|
| `kafka_server_kafkarequesthandlerpool_requesthandleravgidlepercent` | `cluster`, `broker` | handler pool idle ratio. <30% saturation, <10% critical |

## Quota usage per user per broker instance
| Metric | Labels | Meaning |
|---|---|---|
| `kafka_server_produce_byte_rate` | `cluster`, `instance`, `user` | producer byte rate vs quota |
| `kafka_server_fetch_byte_rate` | `cluster`, `instance`, `user` | consumer byte rate vs quota |
| `kafka_server_request_request_time` | `cluster`, `instance`, `user` | request time percentage vs quota |

## Usage per topic per broker instance
`*persec` come from Yammer Meters. JMX exporter may expose raw monotonic `Count` (wrap in `rate()`)
or precomputed `OneMinuteRate`/`MeanRate` (`rate()` of that = meaningless near-zero). Unconfirmed
which here. Check one series: not monotonic between scrapes → use directly, no `rate()`.
| Metric | Labels | Meaning |
|---|---|---|
| `kafka_server_brokertopicmetrics_totalproducerequestspersec` | `cluster`, `instance`, `topic` | produce requests/s |
| `kafka_server_brokertopicmetrics_totalfetchrequestspersec` | `cluster`, `instance`, `topic` | fetch requests/s |
