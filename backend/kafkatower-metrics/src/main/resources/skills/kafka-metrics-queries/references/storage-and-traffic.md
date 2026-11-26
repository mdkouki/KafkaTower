# Storage and Traffic

Run only when the question asks about disk, capacity or log size (storage), or about network or request throughput (traffic).

## Storage
```promql
kminion_kafka_broker_log_dir_size_total_bytes{cluster="<name>"}
kminion_kafka_topic_log_dir_size_total_bytes{cluster="<name>"}
```

## Traffic
```promql
rate(kminion_kafka_received_bytes{cluster="<name>"}[5m])
rate(kminion_kafka_sent_bytes{cluster="<name>"}[5m])
rate(kminion_kafka_requests_received_total{cluster="<name>"}[5m])
```
