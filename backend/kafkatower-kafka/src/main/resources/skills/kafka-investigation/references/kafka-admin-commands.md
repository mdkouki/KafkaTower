# Kafka Admin Client Command Reference

Read-only operations only. These map to what your Kafka Admin Client tool exposes.
Always apply filters (topic name, group ID, principal) to keep output bounded.

## Table of Contents
1. [Cluster & Brokers](#cluster--brokers)
2. [Topics](#topics)
3. [Consumer Groups](#consumer-groups)
4. [ACLs & Principals](#acls--principals)
5. [Configs](#configs)
6. [Output Volume Guide](#output-volume-guide)

---

## Cluster & Brokers

| Operation | What it tells you |
|---|---|
| `list brokers` | IDs, hosts, ports, controller flag |
| `describe cluster` | Cluster ID, controller broker, broker count |

**Token note**: `list brokers` on any cluster returns O(broker count) rows — always safe.

---

## Topics

| Operation | What it tells you |
|---|---|
| `list topics` | All topic names — ⚠️ can be thousands; prefer filtered |
| `describe topic <name>` | Partition leaders, replicas, ISR per partition |
| `describe topic configs <name>` | retention.ms, cleanup.policy, min.insync.replicas, etc. |
| `list topic partitions <name>` | Partition count, offset ranges |
| `describe topic configs bulk <namePattern> <configPattern>` | Config audit across many topics at once (e.g. "which topics have retention.ms under 1 day") — use instead of describing topics one at a time |
| `find topics by criteria <field> <op> <value>` | Global structural/config audit driven by the value itself, not the topic name (e.g. "topics with more than 50 partitions", "replication factor under 3") — evaluates one criterion against every matching topic's live metadata or effective config |

**Use the bulk/criteria tools for global audits.** A question like "which topics have retention
under a day" or "topics with RF < 3" should be answered with one `describeTopicConfigsBulk` or
`findTopicsByCriteria` call, never by calling `describeTopic`/`describeTopicConfigs` once per
topic — both bulk tools already cap their output (`maxTopics`) so they stay bounded.

**Compression rule for `describe topic`**:
- If < 20 partitions: safe to include full output
- If 20–100 partitions: report only partitions where ISR ≠ replica set
- If > 100 partitions: report only summary (total, healthy count, problem count + list problem partition IDs)

---

## Consumer Groups

| Operation | What it tells you |
|---|---|
| `list consumer groups` | All group IDs — ⚠️ can be large; ask user to filter |
| `describe consumer group <id>` | State, members, assignments, coordinator broker |
| `list consumer group offsets <id>` | Committed offset per topic-partition |

**State values to watch**:
- `Stable` — healthy
- `PreparingRebalance` / `CompletingRebalance` — transitioning; only a problem if persistent
- `Empty` — no active members; check if intentional
- `Dead` — group coordinator lost or group deleted

**Compression rule for `list consumer group offsets`**:
- Emit only: total partitions, partitions with offset = -1 (never committed), and worst-lag partition. Do not enumerate all offsets.

**No bulk describe exists for consumer groups.** `list consumer groups` only bulk-lists IDs —
there is no equivalent of `describeTopicConfigsBulk` for group state/lag. If the question isn't
about one group the user named, do not call `describe consumer group`/`list consumer group
offsets` once per ID returned by `list consumer groups`. Narrow first with a metrics `topk`
query (e.g. `topk(5, sum by (group_id) (kminion_kafka_consumer_group_topic_lag{cluster="<name>"}))`)
and only describe the groups that surfaces.

---

## ACLs & Principals

| Operation | What it tells you |
|---|---|
| `list ACLs for principal User:<name>` | All ACLs for this principal — safe, always filtered |
| `list ACLs for topic <name>` | All principals/bindings (incl. PREFIXED and wildcard) with access to this topic — safe, always filtered |

There is no unfiltered "list all ACLs" operation — both ACL lookups always take a
principal or topic filter, so there's nothing to guard against here.

**ACL fields to check**:
- `permissionType`: ALLOW vs DENY (DENY takes precedence)
- `operation`: READ, WRITE, CREATE, DELETE, ALTER, DESCRIBE, CLUSTER_ACTION
- `patternType`: LITERAL vs PREFIXED (prefixed applies to all resources with that prefix)
- `resourceType`: TOPIC, GROUP, CLUSTER, TRANSACTIONAL_ID

**Common ACL gaps**:
- Missing `DESCRIBE` on topic (consumer can't fetch metadata)
- Missing `READ` on consumer group (consumer can't join group)
- Missing `CLUSTER_ACTION` for inter-broker replication (rarely a user issue)

---

## Configs

| Operation | What it tells you |
|---|---|
| `describe broker configs <id>` | All dynamic + static broker configs |
| `describe topic configs <name>` | Topic-level overrides |
| `describe client quota for user <name>` | Produce/fetch byte-rate quotas for a user principal |
| `describe client quota for client-id <id>` | Quotas by client ID |

**Key config values for investigation**:

| Config | Healthy range | Problem if |
|---|---|---|
| `min.insync.replicas` (topic) | RF - 1 | Equal to RF (any broker down = unproducible) |
| `unclean.leader.election.enable` | false | true (data loss risk) |
| `log.retention.ms` | per SLA | Unexpectedly short = data loss |
| `max.poll.interval.ms` | consumer-side | < processing time = rebalance loops |
| `session.timeout.ms` | consumer-side | < GC pause time = false evictions |

---

## Output Volume Guide

Use this before deciding whether to call an admin command.

| Command | Typical output size | Safe to call raw? |
|---|---|---|
| `list brokers` | 3–50 rows | ✅ Always |
| `describe cluster` | 1 row | ✅ Always |
| `describe topic <name>` (≤20 partitions) | ~20 rows | ✅ |
| `describe topic <name>` (>100 partitions) | 100+ rows | ⚠️ Summarise only |
| `describe consumer group <id>` | members × partitions | ✅ if < 50 members |
| `list consumer group offsets <id>` | partition count rows | ⚠️ Summarise |
| `list ACLs for principal <p>` | bounded to one principal | ✅ |
| `list ACLs for topic <name>` | bounded to one topic | ✅ |
| `list topics` (unfiltered) | Potentially thousands | ❌ Ask user to filter |
| `list consumer groups` (unfiltered) | Potentially hundreds | ⚠️ Only if user asked |
| `describe topic configs bulk` | Capped at `maxTopics` matching topics | ✅ Prefer over per-topic calls for a global audit |
| `find topics by criteria` | Capped at `maxTopics` matching topics | ✅ Prefer over per-topic calls for a global audit |