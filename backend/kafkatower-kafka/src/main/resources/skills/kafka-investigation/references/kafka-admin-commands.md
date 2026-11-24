# Kafka Admin Reference

Read-only. Tool names and parameters are in the tool list. This holds only what tools do not say.

## Large and filtered results
- `listTopics`, `listConsumerGroups` need a regex. Unfiltered is refused. Pick regex from the
  question. None derivable → report that, do not list everything.
- Any list over 5 items → count + 5 + collection key. Use `bulkOperation` (e.g. `filter` on
  `UNDER-REPLICATED`, `group_by`, `count`).
- Global audit ("retention under a day", "RF < 3", "> 50 partitions"): one
  `describeTopicConfigsBulk` or `findTopicsByCriteria` call. Never `describeTopic` /
  `describeTopicConfigs` per topic. `describeTopicConfigsBulk` caps topics described (`maxTopics`).
- No bulk describe for consumer groups. Not per ID from `listConsumerGroups`. See Entity scale.
- `listConsumerGroupOffsets` already summarises: total lag, lag by topic, never-committed, worst
  partition. Do not enumerate offsets.

## Consumer group state
- `Stable`: healthy.
- `PreparingRebalance` / `CompletingRebalance`: transitioning. Problem only if persistent.
- `Empty`: no members. Check if intentional.
- `Dead`: coordinator lost or group deleted.

## ACLs
- No unfiltered list. Both lookups take a principal or topic.
- `permissionType`: DENY beats ALLOW.
- `operation`: READ, WRITE, CREATE, DELETE, ALTER, DESCRIBE, CLUSTER_ACTION.
- `patternType`: PREFIXED covers all resources with prefix, LITERAL exact. Prefix `my-` does not
  cover `my-topic`.
- `resourceType`: TOPIC, GROUP, CLUSTER, TRANSACTIONAL_ID.
- Common gaps: no `DESCRIBE` on topic (consumer cannot fetch metadata). No `READ` on group
  (consumer cannot join). No `CLUSTER_ACTION` (inter-broker replication, rarely user issue).

## Key configs
| Config | Healthy | Problem if |
|---|---|---|
| `min.insync.replicas` (topic) | RF - 1 | = RF: one broker down = unproducible |
| `unclean.leader.election.enable` | false | true: data loss risk |
| `log.retention.ms` | per SLA | unexpectedly short: data loss |
| `max.poll.interval.ms` (consumer) | | < processing time: rebalance loops |
| `session.timeout.ms` (consumer) | | < GC pause: false evictions |
