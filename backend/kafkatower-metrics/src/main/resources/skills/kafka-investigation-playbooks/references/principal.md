# Playbook — Principal

Input: principal in Kafka format (`User:<name>`, or `User:CN=...` for mTLS).

### 1 — Admin: ACLs and quotas, same turn
```
listAclsForPrincipal(cluster, principal)
describeClientQuotaByUser(cluster, userName)       # user without "User:" prefix
describeClientQuotaByClientId(cluster, clientId)   # if throttled per client-id instead
```
- ACLs: which topics/groups can it produce/consume? DENY overriding ALLOW? Cluster-level ACLs
  (CreateTopics, AlterConfigs, Describe)?
- Quotas: `producer_byte_rate`, `consumer_byte_rate`, `request_percentage`. Very low quota =
  throttling that looks like slow consumer.

### 2 — Interpret before metrics
| Admin finding | Metrics |
|---|---|
| Missing ACL for topic | authorization failures (Step 3) |
| DENY ACL present | same, look for spike matching the access attempt |
| Very low quota | throttle time (Step 3, unconfirmed names) |
| Certs valid, ACLs correct | check `ssl.client.auth` via `describeBrokerConfigs` |

### 3 — Metrics: pick per Step 2, batch all you need in one turn
```promql
# authorization failures by broker
sum by (broker) (rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error=~"TOPIC_AUTHORIZATION_FAILED|GROUP_AUTHORIZATION_FAILED|CLUSTER_AUTHORIZATION_FAILED"}[5m]))
# authentication failures
sum(rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error="SASL_AUTHENTICATION_FAILED"}[5m]))
# throttle time per client, non-zero = throttled. Unconfirmed names
kafka_server_produce_throttle_time_ms_mean{cluster="<name>", client_id="<clientId>"}
kafka_server_fetch_throttle_time_ms_mean{cluster="<name>", client_id="<clientId>"}
```
Do not use `kafka_server_brokertopicmetrics_unauthorizedrequestspersec`: not a real MBean.
`kafka_server_socketservermetrics_failed_authentication_total` unconfirmed: exporter may rename.
Verify with `label_values(__name__)` filtered on `kafka_server_socket` first.

### 4 — Correlate
Principal tied to known group/producer: fetch `consumer-group` playbook for group, `topic` playbook
for target topics. Compare lag spike timing with auth failure spike timing.
