---
name: kafka-investigator
description: "Use this skill to investigate a Kafka issue end to end: a suspected or confirmed problem, an anomaly, a result that contradicts another source, or an explicit request to troubleshoot or find a root cause. Covers the admin-first, metrics-second ordering, entity-scale discipline, the admin tool usage rules and the metrics rules. A plain status question with no sign of a problem does not need it."
---

# Kafka Investigator

Use when there is an issue: suspected/confirmed problem, anomaly, contradictory result from another
source, or explicit ask to investigate/troubleshoot/find root cause. Topic alone (lag, ISR, ACLs,
capacity...) is not the trigger. Issue, or ask to dig, is.

## Sources
- Kafka Admin tools: ground truth, live state now.
- Metrics tools (`metrics`, `query`, `query_range`, ...): history, trends, rates, anomaly timing.

## Admin first, metrics second
Only fixed ordering in this skill. Metrics mean something only after current state known.
1. Admin tools: what is true now.
2. Interpret: which history actually needed.
3. Metric discovery: confirm names exist.
4. Targeted metric queries: only what admin findings raised.
Never query metrics before admin tools.

## Entity scale
Never call per-entity tool (`describeTopic`, `describeConsumerGroup`, `listConsumerGroupOffsets`,
`describeTopicConfigs`...) in a loop over a population of unknown size.
- User named entities (topics, group IDs): investigate exactly those.
- Otherwise narrow first, touch only what narrowing surfaced:
  - Topics: `describeTopicConfigsBulk` / `findTopicsByCriteria`, not per-topic calls.
  - Consumer groups: no bulk describe. `listConsumerGroups` lists IDs only. Narrow with metrics
    `topk(5, sum by (group_id) (kminion_kafka_consumer_group_topic_lag{cluster="<name>"}))`, then
    `describeConsumerGroup` / `listConsumerGroupOffsets` on those few only.
  - Partitions: `listTopicPartitions` returns all partitions of one topic in one call. Many topics →
    `findTopicsByCriteria` or metrics first to find problem topics.
- Brokers exempt: count small. `listBrokers`, `describeBrokerConfigs` for the few that matter.

## Admin tool usage
- `listTopics`, `listConsumerGroups` need a regex. Pick it from the question; "all" or "how many"
  → `.*`. Always call the tool: if it rejects the call, follow its message and retry. Never answer
  "unavailable" without having called it.
- Any list over 5 items → count + 5 + collection key. Use `bulkOperation` (e.g. `filter` on
  `UNDER-REPLICATED`, `group_by`, `count`).
- Global audit ("retention under a day", "RF < 3", "> 50 partitions"): one
  `describeTopicConfigsBulk` or `findTopicsByCriteria` call. Never `describeTopic` /
  `describeTopicConfigs` per topic. `describeTopicConfigsBulk` caps topics described (`maxTopics`).
- No bulk describe for consumer groups. Not per ID from `listConsumerGroups`. See Entity scale.
- `listConsumerGroupOffsets` already summarises: total lag, lag by topic, never-committed, worst
  partition. Do not enumerate offsets.

## Metrics rules
Every PromQL query has `cluster="<name>"`. Name only from skill `kafka-metrics-catalog` (read the
matching reference) this turn.
Prefix: `kminion_*` = consumer group lag/state/offsets/members, topic lag. `kafka_*` = broker
health, replication, JVM, request latency, controller, leadership, auth failures, quota throttle.

## Reference skills
Load only what the question needs, before building queries or interpreting results. Each skill
lists its own references and when to read them.
- `kafka-investigation-playbooks`: the playbook for the scope under investigation. Usually one;
  `cluster.md` when the scope is ambiguous.
- `kafka-metrics-catalog`: exact metric names and labels, before building PromQL.
- `kafka-admin-reference`: what group states, ACL results and config values mean.
