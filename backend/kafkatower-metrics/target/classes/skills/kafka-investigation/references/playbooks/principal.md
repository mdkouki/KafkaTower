# Investigation Playbook — Principal

**Input needed**: principal name in Kafka format (`User:<name>` or `User:CN=...` for mTLS).

### Step 1 — Admin: ACL enumeration and quota configuration — fire together

Neither call depends on the other's result, so issue both in the same turn:

```
listAclsForPrincipal(cluster, principal)
describeClientQuotaByUser(cluster, userName)       — quota keyed on the user principal (without the "User:" prefix)
describeClientQuotaByClientId(cluster, clientId)    — quota keyed on client-id, if the principal's traffic is throttled per client-id instead
```

From `listAclsForPrincipal`, check:
- What topics/groups can this principal produce to / consume from?
- Are there DENY ACLs overriding ALLOW?
- Cluster-level ACLs (CreateTopics, AlterConfigs, Describe)

From the quota calls, check: `producer_byte_rate`, `consumer_byte_rate`, `request_percentage`.
A very low quota causes throttling that looks like a slow consumer.

### Step 2 — Interpret admin findings before querying metrics

| Admin finding | What metrics to query |
|---|---|
| Missing ACL for topic | `kafka_network_request_metrics_errorpersec{cluster="<name>", error=~"TOPIC_AUTHORIZATION_FAILED\|GROUP_AUTHORIZATION_FAILED"}` |
| DENY ACL present | Same — look for spike coinciding with the access attempt |
| Very low quota configured | `kafka_server_produce/fetch_throttle_time_ms_mean` (unconfirmed name — see below) |
| Certs valid, ACLs correct | Check `ssl.client.auth` config in describeBrokerConfigs |

### Step 3 — Metrics: pick per Step 2's table, batch whichever you need

Every block below is independent of every other, so once you know which block(s) Step 2 calls
for, issue all of them together in one turn rather than one block per turn.

`kafka_server_brokertopicmetrics_unauthorizedrequestspersec` is NOT a real Kafka MBean —
`BrokerTopicMetrics` has no `UnauthorizedRequestsPerSec` attribute. The catalogued,
already-verified-in-shape signal for authorization failures is the same error-rate metric used
everywhere else, filtered to the authorization error codes. Likewise
`kafka_server_socketservermetrics_failed_authentication_total` is unconfirmed against a live
instance (see the verification note in the metrics catalog) — the real MBean is
`kafka.server:type=socket-server-metrics,...` with a `failed-authentication-total` attribute,
which the exporter may render under a different name than this; verify with
`label_values(__name__)` filtered on `kafka_server_socket` before relying on it.

```promql
# Authorization failures cluster-wide, by broker
sum by (broker) (rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error=~"TOPIC_AUTHORIZATION_FAILED|GROUP_AUTHORIZATION_FAILED|CLUSTER_AUTHORIZATION_FAILED"}[5m]))

# Authentication failures cluster-wide — SASL error code is a good corroborating signal
# even if the socket-server-metrics name above turns out to differ
sum(rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error="SASL_AUTHENTICATION_FAILED"}[5m]))

# Produce throttle time for a specific client (non-zero = being throttled) — unconfirmed name
kafka_server_produce_throttle_time_ms_mean{cluster="<name>", client_id="<clientId>"}

# Fetch throttle time — unconfirmed name
kafka_server_fetch_throttle_time_ms_mean{cluster="<name>", client_id="<clientId>"}
```

### Step 4 — Correlate with affected resource

If the principal is associated with a known consumer group or producer:
- Fetch the `consumer-group` playbook for the group.
- Fetch the `topic` playbook for the target topics.
- Compare lag spike timing vs. auth failure spike timing in metrics.
