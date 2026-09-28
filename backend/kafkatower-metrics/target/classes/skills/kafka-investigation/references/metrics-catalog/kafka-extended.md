# Kafka Metrics Catalog — Extended (`kafka_*`, less common)

These cover consumer client-side metrics, group coordination, request handler saturation,
per-user quotas, and per-topic broker usage — reach for the core catalog first (error rate,
controller, replica manager, partition topology); come here for the less common cases.
(Cluster-label and no-guessing-names rules are in your system prompt, not repeated per category
here.)

> **Verification status:** these `kafka_*` (JMX-exporter-derived) entries are NOT currently
> verifiable against a live instance — the local/dev VictoriaMetrics backend exposes zero
> `kafka_*` series right now. Treat any name below as provisional until confirmed with
> `label_values(__name__)` filtered on `kafka_` against the target cluster's real
> VictoriaMetrics instance.

---

## Kafka — Consumer Client-side (`kafka_consumer_*`)

Exported only if consumer JMX metrics are scraped.

| Metric | Key labels | Description |
|---|---|---|
| `kafka_consumer_consumer_coordinator_metrics_commit_total` | `cluster`, `client_id`, `group_id` | Offset commits via consumer coordinator |
| `kafka_consumer_fetch_manager_records_lag_max` | `cluster`, `client_id`, `topic`, `partition` | Max fetch lag reported by the consumer |

---

## Kafka — Group Coordinator (`kafka_coordinator_group_*`)

Broker-side group coordination metrics (join/sync/heartbeat/rebalance rates). Many metrics present.

---

## Kafka — Request Handler (`kafka_server_kafkarequesthandlerpool_*`)

| Metric | Key labels | Description |
|---|---|---|
| `kafka_server_kafkarequesthandlerpool_requesthandleravgidlepercent` | `cluster`, `broker` | Request handler thread pool idle ratio. < 30% = saturation, < 10% = critical |

---

## Kafka — Quotas usage per user per broker instance

| Metric                                                       | Key labels                     | Description                                                                                |
|--------------------------------------------------------------|---------------------------------|--------------------------------------------------------------------------------------------|
| `kafka_server_produce_byte_rate`                             | `cluster`, `instance`, `user`  | Producer byte rate, producer quotas usage per user per broker instance                     |
| `kafka_server_fetch_byte_rate`                               | `cluster`, `instance`, `user`  | Consumer byte rate, consumer quotas usage per user per broker instance                     |
| `kafka_server_request_request_time`                          | `cluster`, `instance`, `user`  | Request time percentage, request time percentage quotas usage per user per broker instance |

---

## Kafka — usage per topic per kafka broker instance

> **`rate()` caveat:** these `*persec` names are exported from Yammer `Meter`s, which the JMX
> exporter can expose either as the raw monotonic `Count` attribute (correct to wrap in
> `rate()`) or as an already-computed `OneMinuteRate`/`MeanRate` (wrapping THAT in `rate()`
> gives a meaningless near-zero result — a rate of a rate). Which one this environment exports
> is unconfirmed (see verification note above); check one series before trusting `rate()` on
> any `*persec` metric here — if it isn't monotonically increasing between scrapes, use it
> directly instead of wrapping it.

| Metric                                                       | Key labels                     | Description                                                                                |
|--------------------------------------------------------------|---------------------------------|--------------------------------------------------------------------------------------------|
| `kafka_server_brokertopicmetrics_totalproducerequestspersec` | `cluster`, `instance`, `topic` | produce request per second by topic by broker instance                                     |
| `kafka_server_brokertopicmetrics_totalfetchrequestspersec`   | `cluster`, `instance`, `topic` | consume request per second by topic by broker instance                                     |
