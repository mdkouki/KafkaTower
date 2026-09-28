# Investigation Playbook — Common Failure Patterns & Runbooks

### Pattern: Protocol errors / error rate spike

1. **Step 4a first**: `sum(rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error!="NONE"}[5m]))` — confirm non-zero.
2. Break down by request type (Step 4b) — `Produce` errors → producer-side; `Fetch` errors → consumer-side; `Metadata` errors → discovery problems.
3. Break down by error code (Step 4c) — `NOT_LEADER_OR_FOLLOWER` → leader election in progress; `TOPIC_AUTHORIZATION_FAILED` → ACL issue → go to the `principal` playbook; `NOT_ENOUGH_REPLICAS` → ISR below min.insync.replicas → go to the `topic` playbook.
4. Break down by broker (Step 4d) — if one broker accounts for >80% of errors → go to the `broker` playbook.
5. Correlate timing with `kminion_kafka_consumer_group_info` state changes or `kafka_cluster_partition_underreplicated` spikes.

### Pattern: Consumer lag spike

0. **If no specific group was named**, do not run this pattern once per group in the cluster —
   first narrow with `topk(5, sum by (group_id) (kminion_kafka_consumer_group_topic_lag{cluster="<name>"}))`
   and only continue with the group(s) that surfaces.
1. **Admin**: `describeConsumerGroup` — is group Stable or rebalancing?
2. If rebalancing: `kminion_kafka_consumer_group_info{cluster="<name>", group_id}` state timeline — how long?
3. If Stable with growing lag: `deriv(sum(kminion_kafka_consumer_group_topic_lag{cluster="<name>", cluster, group_id})[15m:1m])` — which topics?
4. **Admin**: `describeTopic` for affected topics — any partitions under-replicated?
5. If under-replicated: `kafka_cluster_partition_underreplicated{cluster="<name>", topic}` — confirm and go to the `broker` playbook.
6. Single-partition lag spike: `sort_desc(kminion_kafka_consumer_group_topic_partition_lag{cluster="<name>", group_id})` — hot partition or slow consumer?

### Pattern: Under-replicated partitions

1. **Admin**: `describeCluster` — identify which broker is absent from ISRs.
2. Narrow to the affected topics via `kafka_cluster_partition_underreplicated{cluster="<name>"} > 0`
   (or `findTopicsByCriteria` if you need the full picture) before touching any topic
   individually — never call `describeTopic` per topic across the whole cluster.
3. **Admin**: `describeTopic` for just those affected topics — which specific partitions?
4. Confirm broker traffic: `kminion_kafka_received_bytes{cluster="<name>", broker_id}` — is it receiving anything?
5. Check request handler: `kafka_server_kafkarequesthandlerpool_requesthandleravgidlepercent{cluster="<name>", broker}`.
6. If broker is healthy but lagging: check `kafka_cluster_partition_insyncreplicascount{cluster="<name>"} < kafka_cluster_partition_replicascount{cluster="<name>"}` for the affected partitions. `kafka_cluster_partition_firstfetchfromleader` is non-zero for every follower that has ever fetched — it is not a fault signal on its own; only use it, with a large threshold (e.g. `> 30000`), to spot a follower that hasn't fetched recently at all.

### Pattern: Authorization failures

1. **Admin**: `listAclsForPrincipal` — are expected ACLs present?
2. Check for DENY ACLs overriding ALLOW (DENY always wins in Kafka ACL evaluation).
3. Check prefix vs. literal match: a prefix ACL on `topic:my-` does not cover `my-topic`.
4. **Admin**: `describeBrokerConfigs` → check `allow.everyone.if.no.acl.found`.
5. Query `kafka_network_request_metrics_errorpersec{cluster="<name>", error=~"TOPIC_AUTHORIZATION_FAILED|GROUP_AUTHORIZATION_FAILED|CLUSTER_AUTHORIZATION_FAILED"}` to confirm spike timing.

### Pattern: Frequent rebalances

**Input needed**: a specific, already-identified group. If no group was named, narrow first with
the "Consumer lag spike" pattern's Step 0 metrics query, or `kminion_kafka_consumer_group_info`
filtered to groups whose `state` isn't Stable — do not loop `describeConsumerGroup` across every
group in the cluster looking for churn.

1. **Admin**: `describeConsumerGroup` — call it more than once, spaced over time, for THIS ONE
   group to observe membership churn (repeated polling of a single group, not a loop over many
   groups).
2. `kminion_kafka_consumer_group_info{cluster="<name>", group_id, state}` — how often does it leave Stable?
3. `kminion_kafka_consumer_group_empty_members{cluster="<name>", group_id}` — are members joining with no assignments?
4. Check consumer `session.timeout.ms` vs. `heartbeat.interval.ms`.
5. Check if `max.poll.interval.ms` is being exceeded (slow processing between polls).
6. Look for rolling restarts or deployments of the consumer application.
