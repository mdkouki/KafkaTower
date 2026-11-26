# Playbook — Failure Patterns

### Error rate spike
1. Cluster playbook 4a: `sum(rate(kafka_network_request_metrics_errorpersec{cluster="<name>", error!="NONE"}[5m]))`. Confirm non-zero.
2. By request (4b): `Produce` → producer-side. `Fetch` → consumer-side. `Metadata` → discovery.
3. By error (4c): `NOT_LEADER_OR_FOLLOWER` → leader election running. `TOPIC_AUTHORIZATION_FAILED` →
   ACL, `principal` playbook. `NOT_ENOUGH_REPLICAS` → ISR below `min.insync.replicas`, `topic` playbook.
4. By broker (4d): one broker > 80% of errors → `broker` playbook.
5. Correlate timing with `kminion_kafka_consumer_group_info` state changes or
   `kafka_cluster_partition_underreplicated` spikes.

### Consumer lag spike
0. No group named → do not run per group. Narrow first:
   `topk(5, sum by (group_id) (kminion_kafka_consumer_group_topic_lag{cluster="<name>"}))`.
1. Admin `describeConsumerGroup`: Stable or rebalancing?
2. Rebalancing → `kminion_kafka_consumer_group_info{cluster="<name>", group_id="<id>"}` state timeline. How long?
3. Stable, lag growing → `deriv(sum(kminion_kafka_consumer_group_topic_lag{cluster="<name>", group_id="<id>"})[15m:1m])`. Which topics?
4. Admin `describeTopic` on affected topics: under-replicated partitions?
5. Yes → `kafka_cluster_partition_underreplicated{cluster="<name>", topic="<topic>"}` confirm, `broker` playbook.
6. One partition spike → `sort_desc(kminion_kafka_consumer_group_topic_partition_lag{cluster="<name>", group_id="<id>"})`. Hot partition or slow consumer?

### Under-replicated partitions
1. Admin `describeCluster`: which broker absent from ISRs?
2. Narrow: `kafka_cluster_partition_underreplicated{cluster="<name>"} > 0` (or `findTopicsByCriteria`).
   Never `describeTopic` per topic across cluster.
3. Admin `describeTopic` on just those: which partitions?
4. Broker receiving? `kminion_kafka_received_bytes{cluster="<name>", broker_id="<id>"}`.
5. Handler: `kafka_server_kafkarequesthandlerpool_requesthandleravgidlepercent{cluster="<name>", broker="<id>"}`.
6. Healthy but lagging → ISR vs replicas for affected partitions:
   `kafka_cluster_partition_insyncreplicascount{cluster="<name>"} < kafka_cluster_partition_replicascount{cluster="<name>"}`.
   `kafka_cluster_partition_firstfetchfromleader` is non-zero for every follower that ever fetched.
   Not a fault signal alone. Only with large threshold (`> 30000`) to find follower not fetching recently.

### Authorization failures
1. Admin `listAclsForPrincipal`: expected ACLs present?
2. DENY overriding ALLOW? DENY always wins.
3. Prefix vs literal: prefix ACL on `my-` does not cover `my-topic`.
4. Admin `describeBrokerConfigs`: `allow.everyone.if.no.acl.found`.
5. Confirm spike timing: `kafka_network_request_metrics_errorpersec{cluster="<name>", error=~"TOPIC_AUTHORIZATION_FAILED|GROUP_AUTHORIZATION_FAILED|CLUSTER_AUTHORIZATION_FAILED"}`.

### Frequent rebalances
Input: one identified group. None named → narrow first: lag-spike Step 0, or
`kminion_kafka_consumer_group_info{cluster="<name>", state!="Stable"}`. No `describeConsumerGroup`
loop over cluster.
1. Admin `describeConsumerGroup` on THIS group, repeated spaced over time: membership churn.
2. `kminion_kafka_consumer_group_info{cluster="<name>", group_id="<id>"}`: how often leaves Stable?
3. `kminion_kafka_consumer_group_empty_members{cluster="<name>", group_id="<id>"}`: members joining with no assignment?
4. Consumer `session.timeout.ms` vs `heartbeat.interval.ms`.
5. `max.poll.interval.ms` exceeded (slow processing between polls)?
6. Rolling restarts or deploys of consumer app?
