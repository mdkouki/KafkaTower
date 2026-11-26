# Kafka Admin Reference — ACLs

Read-only. Tool names and parameters are in the tool list. This holds only what tools do not say.

- No unfiltered list. Both lookups take a principal or topic.
- `permissionType`: DENY beats ALLOW.
- `operation`: READ, WRITE, CREATE, DELETE, ALTER, DESCRIBE, CLUSTER_ACTION.
- `patternType`: PREFIXED covers all resources with prefix, LITERAL exact. Prefix `my-` does not
  cover `my-topic`.
- `resourceType`: TOPIC, GROUP, CLUSTER, TRANSACTIONAL_ID.
- Common gaps: no `DESCRIBE` on topic (consumer cannot fetch metadata). No `READ` on group
  (consumer cannot join). No `CLUSTER_ACTION` (inter-broker replication, rarely user issue).
