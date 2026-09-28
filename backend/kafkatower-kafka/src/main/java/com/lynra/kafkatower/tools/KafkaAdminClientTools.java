package com.lynra.kafkatower.tools;

import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.api.annotation.EmbabelComponent;
import com.lynra.kafkatower.kafka.AdminClientErrors;
import com.lynra.kafkatower.kafka.GroupInfo;
import com.lynra.kafkatower.kafka.GroupLookupException;
import com.lynra.kafkatower.kafka.GroupService;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.acl.*;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.quota.ClientQuotaEntity;
import org.apache.kafka.common.quota.ClientQuotaFilter;
import org.apache.kafka.common.quota.ClientQuotaFilterComponent;
import org.apache.kafka.common.resource.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@EmbabelComponent
public class KafkaAdminClientTools {

    private static final Logger log = LoggerFactory.getLogger(KafkaAdminClientTools.class);
    private static final int TIMEOUT_SEC = 10;

    private final Map<String, AdminClient> kafkaAdminMap;
    private final GroupService groupService;

    public KafkaAdminClientTools(Map<String, AdminClient> kafkaAdminMap, GroupService groupService) {
        this.kafkaAdminMap = kafkaAdminMap;
        this.groupService = groupService;
    }

    private AdminClient admin(String cluster) {
        AdminClient client = kafkaAdminMap.get(cluster);
        if (client == null) throw new IllegalArgumentException("Unknown cluster: " + cluster);
        return client;
    }

    // -----------------------------------------------------------------------
    // Cluster & Brokers
    // -----------------------------------------------------------------------

    @LlmTool(description = "List all brokers in the cluster with their IDs, hosts, ports, and which is the controller. Always safe to call. Parameter clusterName: name of the Kafka cluster.")
    public String listBrokers(String clusterName) {
        try {
            DescribeClusterResult result = admin(clusterName).describeCluster();
            Collection<Node> nodes = result.nodes().get(TIMEOUT_SEC, TimeUnit.SECONDS);
            Node controller = result.controller().get(TIMEOUT_SEC, TimeUnit.SECONDS);
            StringBuilder sb = new StringBuilder("Brokers in cluster '").append(clusterName)
                    .append("' (").append(nodes.size()).append("):\n");
            for (Node node : nodes) {
                sb.append("  broker ").append(node.id())
                        .append("  ").append(node.host()).append(":").append(node.port());
                if (controller != null && node.id() == controller.id()) sb.append("  [controller]");
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("listBrokers failed for cluster: {}", clusterName, e);
            return errorMessage(e);
        }
    }

    @LlmTool(description = "Describe the cluster: cluster ID, controller broker, and total broker count. Always safe to call. Parameter clusterName: name of the Kafka cluster.")
    public String describeCluster(String clusterName) {
        try {
            DescribeClusterResult result = admin(clusterName).describeCluster();
            String clusterId = result.clusterId().get(TIMEOUT_SEC, TimeUnit.SECONDS);
            Node controller = result.controller().get(TIMEOUT_SEC, TimeUnit.SECONDS);
            int brokerCount = result.nodes().get(TIMEOUT_SEC, TimeUnit.SECONDS).size();
            return "Cluster: " + clusterName +
                    "\n  cluster-id: " + clusterId +
                    "\n  controller: broker " + (controller != null ? controller.id() + " (" + controller.host() + ":" + controller.port() + ")" : "unknown") +
                    "\n  broker count: " + brokerCount + "\n";
        } catch (Exception e) {
            log.error("describeCluster failed for cluster: {}", clusterName, e);
            return errorMessage(e);
        }
    }

    @LlmTool(description = "Describe dynamic and non-default static configs for a specific broker (listeners, log dirs, thread counts, etc.). Parameter clusterName: name of the Kafka cluster. Parameter brokerId: broker ID (integer).")
    public String describeBrokerConfigs(String clusterName, int brokerId) {
        try {
            ConfigResource resource = new ConfigResource(ConfigResource.Type.BROKER, String.valueOf(brokerId));
            Map<ConfigResource, Config> configs = admin(clusterName)
                    .describeConfigs(List.of(resource))
                    .all().get(TIMEOUT_SEC, TimeUnit.SECONDS);
            Config config = configs.get(resource);
            if (config == null) return "Broker " + brokerId + " not found in cluster '" + clusterName + "'.";
            StringBuilder sb = new StringBuilder("Broker ").append(brokerId).append(" configs:\n");
            config.entries().stream()
                    .filter(e -> !e.isDefault() || e.source() == ConfigEntry.ConfigSource.DYNAMIC_BROKER_CONFIG)
                    .filter(e -> !isSensitiveConfig(e))
                    .sorted(Comparator.comparing(ConfigEntry::name))
                    .forEach(e -> sb.append("  ").append(e.name()).append(" = ").append(e.value()).append("\n"));
            return sb.toString();
        } catch (Exception e) {
            log.error("describeBrokerConfigs failed for broker {} in cluster: {}", brokerId, clusterName, e);
            return errorMessage(e);
        }
    }

    // -----------------------------------------------------------------------
    // Topics
    // -----------------------------------------------------------------------

    @LlmTool(description = """
            List topic names in the cluster, filtered by prefix. A non-empty prefix is REQUIRED
            — this tool refuses to list all topics unfiltered, since a large cluster can return
            thousands of names and flood context. Narrow the prefix based on what the question is
            actually about instead of listing everything and scanning the result yourself.
            Parameter clusterName: name of the Kafka cluster.
            Parameter prefix: required, non-empty topic name prefix filter.
            """)
    public String listTopics(String clusterName, String prefix) {
        if (prefix == null || prefix.isBlank()) {
            log.warn("listTopics rejected for cluster {}: no prefix given", clusterName);
            return "A non-empty prefix is required — pass the specific topic-name prefix the " +
                    "question is about instead of listing every topic in the cluster.";
        }
        try {
            Set<String> allTopics = admin(clusterName).listTopics().names().get(TIMEOUT_SEC, TimeUnit.SECONDS);
            List<String> filtered = allTopics.stream()
                    .filter(t -> t.startsWith(prefix))
                    .sorted()
                    .collect(Collectors.toList());
            if (filtered.isEmpty())
                return "No topics found matching prefix '" + prefix + "' in cluster '" + clusterName + "'.";
            StringBuilder sb = new StringBuilder("Topics in '").append(clusterName)
                    .append("' matching prefix '").append(prefix).append("' (").append(filtered.size()).append("):\n");
            filtered.forEach(t -> sb.append("  - ").append(t).append("\n"));
            return sb.toString();
        } catch (Exception e) {
            log.error("listTopics failed for cluster: {}", clusterName, e);
            return errorMessage(e);
        }
    }

    @LlmTool(description = """
            Describe a topic: partition count, leaders, replicas, and ISR per partition.
            Output is automatically compressed: full detail for <20 partitions, under-replicated only for 20-100, summary only for >100.
            Parameter clusterName: name of the Kafka cluster.
            Parameter topicName: topic name.
            """)
    public String describeTopic(String clusterName, String topicName) {
        try {
            Map<String, TopicDescription> desc = admin(clusterName)
                    .describeTopics(List.of(topicName))
                    .allTopicNames().get(TIMEOUT_SEC, TimeUnit.SECONDS);
            TopicDescription topic = desc.get(topicName);
            if (topic == null) return "Topic '" + topicName + "' not found in cluster '" + clusterName + "'.";

            List<TopicPartitionInfo> partitions = topic.partitions();
            int total = partitions.size();
            List<TopicPartitionInfo> underReplicated = partitions.stream()
                    .filter(p -> p.isr().size() < p.replicas().size())
                    .collect(Collectors.toList());

            StringBuilder sb = new StringBuilder("Topic: ").append(topicName)
                    .append("  partitions: ").append(total)
                    .append("  internal: ").append(topic.isInternal()).append("\n");

            if (total <= 20) {
                for (TopicPartitionInfo p : partitions) {
                    sb.append("  partition ").append(p.partition())
                            .append("  leader: ").append(p.leader() != null ? p.leader().id() : "none")
                            .append("  replicas: ").append(nodeIds(p.replicas()))
                            .append("  isr: ").append(nodeIds(p.isr()));
                    if (p.isr().size() < p.replicas().size()) sb.append("  [UNDER-REPLICATED]");
                    sb.append("\n");
                }
            } else if (total <= 100) {
                sb.append("  (showing only under-replicated partitions)\n");
                if (underReplicated.isEmpty()) {
                    sb.append("  All ").append(total).append(" partitions are fully replicated.\n");
                } else {
                    for (TopicPartitionInfo p : underReplicated) {
                        sb.append("  partition ").append(p.partition())
                                .append("  leader: ").append(p.leader() != null ? p.leader().id() : "none")
                                .append("  replicas: ").append(nodeIds(p.replicas()))
                                .append("  isr: ").append(nodeIds(p.isr()))
                                .append("  [UNDER-REPLICATED]\n");
                    }
                }
            } else {
                int healthy = total - underReplicated.size();
                sb.append("  (summary only — ").append(total).append(" partitions)\n")
                        .append("  healthy: ").append(healthy)
                        .append("  under-replicated: ").append(underReplicated.size()).append("\n");
                if (!underReplicated.isEmpty()) {
                    sb.append("  under-replicated IDs: ")
                            .append(underReplicated.stream()
                                    .map(p -> String.valueOf(p.partition()))
                                    .collect(Collectors.joining(", ")))
                            .append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("describeTopic failed for topic {} in cluster: {}", topicName, clusterName, e);
            return errorMessage(e);
        }
    }

    @LlmTool(description = "Describe topic-level config overrides: retention.ms, cleanup.policy, min.insync.replicas, etc. Parameter clusterName: name of the Kafka cluster. Parameter topicName: topic name.")
    public String describeTopicConfigs(String clusterName, String topicName) {
        try {
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topicName);
            Map<ConfigResource, Config> configs = admin(clusterName)
                    .describeConfigs(List.of(resource))
                    .all().get(TIMEOUT_SEC, TimeUnit.SECONDS);
            Config config = configs.get(resource);
            if (config == null) return "Topic '" + topicName + "' not found in cluster '" + clusterName + "'.";
            StringBuilder sb = new StringBuilder("Configs for topic '").append(topicName).append("':\n");
            config.entries().stream()
                    .filter(e -> e.source() == ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG || !e.isDefault())
                    .filter(e -> !isSensitiveConfig(e))
                    .sorted(Comparator.comparing(ConfigEntry::name))
                    .forEach(e -> sb.append("  ").append(e.name()).append(" = ").append(e.value()).append("\n"));
            return sb.toString();
        } catch (Exception e) {
            log.error("describeTopicConfigs failed for topic {} in cluster: {}", topicName, clusterName, e);
            return errorMessage(e);
        }
    }

    @LlmTool(description = """
            Bulk config audit across many topics at once — use this instead of describeTopicConfigs
            when the question is about a global config check (e.g. "which topics have retention.ms
            under 1 day", "list topics with cleanup.policy=compact", "show min.insync.replicas for
            all topics starting with orders.").
            Matches topics whose name satisfies topicNamePattern: exact name, prefix, or regex
            (containing any of .* .+ [ ( ? + ^ $ is treated as a regex, matched fully against the
            topic name); pass "" or "*" to match every topic in the cluster.
            For each matching topic, only configs whose name satisfies configNamePattern are
            returned, using the same exact/prefix/regex rule; pass "" or "*" to return all
            non-default configs.
            Output is capped at maxTopics matching topics to keep the response bounded — narrow
            topicNamePattern and re-run if the result says it was truncated.
            Parameter clusterName: name of the Kafka cluster.
            Parameter topicNamePattern: topic name filter — exact name, prefix, or regex; "" or "*" for all topics.
            Parameter configNamePattern: config key filter — exact name, prefix, or regex; "" or "*" for all non-default configs.
            Parameter maxTopics: safety cap on number of matching topics to describe, e.g. 50.
            """)
    public String describeTopicConfigsBulk(String clusterName, String topicNamePattern, String configNamePattern, int maxTopics) {
        try {
            Set<String> allTopics = admin(clusterName).listTopics().names().get(TIMEOUT_SEC, TimeUnit.SECONDS);
            List<String> matchingTopics = allTopics.stream()
                    .filter(t -> matchesPattern(t, topicNamePattern))
                    .sorted()
                    .collect(Collectors.toList());

            if (matchingTopics.isEmpty())
                return "No topics found matching '" + topicNamePattern + "' in cluster '" + clusterName + "'.";

            boolean truncated = matchingTopics.size() > maxTopics;
            List<String> toDescribe = truncated ? matchingTopics.subList(0, maxTopics) : matchingTopics;

            List<ConfigResource> resources = toDescribe.stream()
                    .map(t -> new ConfigResource(ConfigResource.Type.TOPIC, t))
                    .collect(Collectors.toList());
            Map<ConfigResource, Config> configs = admin(clusterName)
                    .describeConfigs(resources)
                    .all().get(TIMEOUT_SEC, TimeUnit.SECONDS);

            StringBuilder sb = new StringBuilder("Configs for ").append(toDescribe.size())
                    .append(" topic(s) matching '").append(topicNamePattern).append("'");
            if (truncated)
                sb.append(" (truncated — ").append(matchingTopics.size())
                        .append(" total matched, showing first ").append(maxTopics).append(")");
            sb.append(":\n");

            int reportedTopics = 0;
            for (String topicName : toDescribe) {
                Config config = configs.get(new ConfigResource(ConfigResource.Type.TOPIC, topicName));
                if (config == null) continue;
                List<ConfigEntry> matchingEntries = config.entries().stream()
                        .filter(e -> e.source() == ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG || !e.isDefault())
                        .filter(e -> !isSensitiveConfig(e))
                        .filter(e -> matchesPattern(e.name(), configNamePattern))
                        .sorted(Comparator.comparing(ConfigEntry::name))
                        .collect(Collectors.toList());
                if (matchingEntries.isEmpty()) continue;
                reportedTopics++;
                sb.append("  ").append(topicName).append(":\n");
                matchingEntries.forEach(e -> sb.append("    ").append(e.name()).append(" = ").append(e.value()).append("\n"));
            }
            if (reportedTopics == 0)
                sb.append("  (none of the matched topics have a config matching '").append(configNamePattern).append("')\n");
            return sb.toString();
        } catch (Exception e) {
            log.error("describeTopicConfigsBulk failed for pattern {} in cluster: {}", topicNamePattern, clusterName, e);
            return errorMessage(e);
        }
    }

    @LlmTool(description = """
            Find topics matching a value criterion — for global audits driven by the config or
            structure itself, not the topic name (e.g. "topics with more than 50 partitions",
            "topics with replication factor under 3", "topics with retention.ms below 1 day",
            "topics with retention.bytes = -1 (unlimited)", "topics with cleanup.policy=compact").
            Optionally narrow the candidate set first with topicNamePattern (exact name, prefix, or
            regex — containing any of .* .+ [ ( ? + ^ $ is treated as regex; "" or "*" for all topics).
            Then evaluates one criterion against every candidate topic:
              - field "partitions": partition count, from live topic metadata.
              - field "replicationFactor": replication factor (from partition 0's replica count),
                from live topic metadata.
              - any other field name (e.g. "retention.ms", "retention.bytes", "cleanup.policy",
                "min.insync.replicas", "segment.bytes", ...): the topic's EFFECTIVE config value —
                an explicit override if set, otherwise the broker/cluster default — same as Kafka
                itself would apply, not just topic-level overrides.
            operator: "eq" | "ne" | "gt" | "gte" | "lt" | "lte" | "contains".
            gt/gte/lt/lte compare both sides as numbers — only use them for numeric fields
            (partitions, replicationFactor, retention.ms, retention.bytes, min.insync.replicas,
            segment.bytes, ...). eq/ne/contains compare as strings and also work for fields like
            cleanup.policy. A topic whose field can't be read (unknown config name, or a non-numeric
            value compared with a numeric operator) is silently excluded, not reported as a match.
            Output is capped at maxTopics matching topics.
            Parameter clusterName: name of the Kafka cluster.
            Parameter topicNamePattern: optional topic name pre-filter; "" or "*" for all topics.
            Parameter field: "partitions", "replicationFactor", or a topic config name.
            Parameter operator: "eq" | "ne" | "gt" | "gte" | "lt" | "lte" | "contains".
            Parameter value: the value to compare against, e.g. "50", "3", "86400000", "compact".
            Parameter maxTopics: safety cap on number of matching topics returned, e.g. 50.
            """)
    public String findTopicsByCriteria(String clusterName, String topicNamePattern, String field, String operator, String value, int maxTopics) {
        try {
            Set<String> allTopics = admin(clusterName).listTopics().names().get(TIMEOUT_SEC, TimeUnit.SECONDS);
            List<String> candidates = allTopics.stream()
                    .filter(t -> matchesPattern(t, topicNamePattern))
                    .sorted()
                    .collect(Collectors.toList());
            if (candidates.isEmpty())
                return "No topics found matching '" + topicNamePattern + "' in cluster '" + clusterName + "'.";

            String op = operator == null ? "" : operator.trim().toLowerCase(Locale.ROOT);
            if (!Set.of("eq", "ne", "gt", "gte", "lt", "lte", "contains").contains(op))
                return "Error: unknown operator '" + operator + "'. Use one of eq, ne, gt, gte, lt, lte, contains.";
            boolean numericOp = Set.of("gt", "gte", "lt", "lte").contains(op);
            if (numericOp && tryParseLong(value) == null)
                return "Error: operator '" + op + "' requires a numeric value, got '" + value + "'.";

            Map<String, String> actualValues = new LinkedHashMap<>();
            boolean structural = field.equalsIgnoreCase("partitions")
                    || field.equalsIgnoreCase("replicationFactor") || field.equalsIgnoreCase("replication.factor");

            if (structural) {
                Map<String, TopicDescription> descs = admin(clusterName)
                        .describeTopics(candidates)
                        .allTopicNames().get(TIMEOUT_SEC, TimeUnit.SECONDS);
                for (String t : candidates) {
                    TopicDescription d = descs.get(t);
                    if (d == null || d.partitions().isEmpty()) continue;
                    long actual = field.equalsIgnoreCase("partitions")
                            ? d.partitions().size()
                            : d.partitions().get(0).replicas().size();
                    actualValues.put(t, String.valueOf(actual));
                }
            } else {
                List<ConfigResource> resources = candidates.stream()
                        .map(t -> new ConfigResource(ConfigResource.Type.TOPIC, t))
                        .collect(Collectors.toList());
                Map<ConfigResource, Config> configs = admin(clusterName)
                        .describeConfigs(resources)
                        .all().get(TIMEOUT_SEC, TimeUnit.SECONDS);
                for (String t : candidates) {
                    Config config = configs.get(new ConfigResource(ConfigResource.Type.TOPIC, t));
                    if (config == null) continue;
                    ConfigEntry entry = config.get(field);
                    if (entry == null || isSensitiveConfig(entry)) continue;
                    actualValues.put(t, entry.value());
                }
            }

            String target = value == null ? "" : value.trim();
            Long targetNumeric = numericOp ? tryParseLong(target) : null;
            List<String> matched = new ArrayList<>();
            int unreadable = candidates.size() - actualValues.size();
            for (Map.Entry<String, String> e : actualValues.entrySet()) {
                boolean isMatch;
                if (numericOp) {
                    Long actualNumeric = tryParseLong(e.getValue());
                    if (actualNumeric == null) continue;
                    isMatch = switch (op) {
                        case "gt" -> actualNumeric > targetNumeric;
                        case "gte" -> actualNumeric >= targetNumeric;
                        case "lt" -> actualNumeric < targetNumeric;
                        default -> actualNumeric <= targetNumeric;
                    };
                } else {
                    isMatch = switch (op) {
                        case "eq" -> e.getValue().equals(target);
                        case "ne" -> !e.getValue().equals(target);
                        default -> e.getValue().contains(target);
                    };
                }
                if (isMatch) matched.add(e.getKey() + " = " + e.getValue());
            }

            StringBuilder sb = new StringBuilder("Topics in '").append(clusterName).append("' where ")
                    .append(field).append(" ").append(op).append(" '").append(target).append("'");
            if (!topicNamePattern.isBlank() && !topicNamePattern.equals("*"))
                sb.append(" (name matching '").append(topicNamePattern).append("')");
            sb.append(": ").append(matched.size()).append(" of ").append(candidates.size()).append(" candidate(s)");
            if (unreadable > 0) sb.append(", ").append(unreadable).append(" excluded (field not found or not numeric)");
            sb.append("\n");

            boolean truncated = matched.size() > maxTopics;
            (truncated ? matched.subList(0, maxTopics) : matched).forEach(m -> sb.append("  - ").append(m).append("\n"));
            if (truncated)
                sb.append("  (truncated — showing first ").append(maxTopics).append(" of ").append(matched.size()).append(" matches)\n");
            return sb.toString();
        } catch (Exception e) {
            log.error("findTopicsByCriteria failed for field {} in cluster: {}", field, clusterName, e);
            return errorMessage(e);
        }
    }

    @LlmTool(description = "List each partition's earliest and latest offsets for a topic, showing the current message count per partition. Parameter clusterName: name of the Kafka cluster. Parameter topicName: topic name.")
    public String listTopicPartitions(String clusterName, String topicName) {
        try {
            Map<String, TopicDescription> desc = admin(clusterName)
                    .describeTopics(List.of(topicName))
                    .allTopicNames().get(TIMEOUT_SEC, TimeUnit.SECONDS);
            TopicDescription topic = desc.get(topicName);
            if (topic == null) return "Topic '" + topicName + "' not found in cluster '" + clusterName + "'.";

            List<TopicPartitionInfo> partitions = topic.partitions();
            Map<TopicPartition, OffsetSpec> earliestSpecs = new LinkedHashMap<>();
            Map<TopicPartition, OffsetSpec> latestSpecs = new LinkedHashMap<>();
            for (TopicPartitionInfo p : partitions) {
                TopicPartition tp = new TopicPartition(topicName, p.partition());
                earliestSpecs.put(tp, OffsetSpec.earliest());
                latestSpecs.put(tp, OffsetSpec.latest());
            }

            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> earliest = admin(clusterName)
                    .listOffsets(earliestSpecs).all().get(TIMEOUT_SEC, TimeUnit.SECONDS);
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latest = admin(clusterName)
                    .listOffsets(latestSpecs).all().get(TIMEOUT_SEC, TimeUnit.SECONDS);

            StringBuilder sb = new StringBuilder("Partitions for topic '").append(topicName)
                    .append("' (").append(partitions.size()).append(" partitions):\n");
            for (TopicPartitionInfo p : partitions) {
                TopicPartition tp = new TopicPartition(topicName, p.partition());
                long start = earliest.containsKey(tp) ? earliest.get(tp).offset() : -1;
                long end = latest.containsKey(tp) ? latest.get(tp).offset() : -1;
                sb.append("  partition ").append(p.partition())
                        .append("  earliest=").append(start)
                        .append("  latest=").append(end)
                        .append("  size=").append(start >= 0 && end >= 0 ? end - start : "?")
                        .append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("listTopicPartitions failed for topic {} in cluster: {}", topicName, clusterName, e);
            return errorMessage(e);
        }
    }

    // -----------------------------------------------------------------------
    // Consumer Groups
    // -----------------------------------------------------------------------

    @LlmTool(description = """
            List consumer group IDs in the cluster, filtered by prefix. A non-empty prefix is
            REQUIRED — this tool refuses to list all groups unfiltered, since a large cluster can
            return thousands of IDs and flood context. Narrow the prefix based on what the
            question is actually about (a service/team name, a known naming convention) instead
            of listing everything and scanning the result yourself.
            Parameter clusterName: name of the Kafka cluster.
            Parameter prefix: required, non-empty prefix filter for group IDs.
            """)
    public String listConsumerGroups(String clusterName, String prefix) {
        if (prefix == null || prefix.isBlank()) {
            log.warn("listConsumerGroups rejected for cluster {}: no prefix given", clusterName);
            return "A non-empty prefix is required — pass the specific group-ID prefix the " +
                    "question is about instead of listing every consumer group in the cluster.";
        }
        try {
            List<String> groups = admin(clusterName).listConsumerGroups().all()
                    .get(TIMEOUT_SEC, TimeUnit.SECONDS).stream()
                    .map(ConsumerGroupListing::groupId)
                    .filter(id -> id.startsWith(prefix))
                    .sorted()
                    .collect(Collectors.toList());
            if (groups.isEmpty())
                return "No consumer groups found matching prefix '" + prefix + "' in cluster '" + clusterName + "'.";
            StringBuilder sb = new StringBuilder("Consumer groups in '").append(clusterName)
                    .append("' matching prefix '").append(prefix).append("' (").append(groups.size()).append("):\n");
            groups.forEach(g -> sb.append("  - ").append(g).append("\n"));
            return sb.toString();
        } catch (Exception e) {
            log.error("listConsumerGroups failed for cluster: {}", clusterName, e);
            return errorMessage(e);
        }
    }

    @LlmTool(description = """
            Describe a consumer group: state, coordinator broker, member count, and per-member partition assignments.
            State values: Stable (healthy), PreparingRebalance/CompletingRebalance (transitioning), Empty (no members), Dead (lost coordinator).
            Parameter clusterName: name of the Kafka cluster.
            Parameter groupId: consumer group ID.
            """)
    public String describeConsumerGroup(String clusterName, String groupId) {
        try {
            GroupInfo info = groupService.getGroupState(groupId, clusterName);
            if (info == null) return "Consumer group '" + groupId + "' not found in cluster '" + clusterName + "'.";

            StringBuilder sb = new StringBuilder("Consumer group: ").append(groupId)
                    .append("\n  state: ").append(info.state())
                    .append("\n  coordinator: ").append(info.coordinator() != null ? info.coordinator() : "unknown")
                    .append("\n  assignor: ").append(info.assignor())
                    .append("\n  members: ").append(info.members().size()).append("\n");

            if (info.members().size() <= 50) {
                for (GroupInfo.MemberInfo m : info.members()) {
                    sb.append("  member ").append(m.clientId())
                            .append(" @ ").append(m.host())
                            .append("  assignments: ").append(String.join(", ", m.assignedPartitions()))
                            .append("\n");
                }
            } else {
                sb.append("  (member list truncated — ").append(info.members().size()).append(" members total)\n");
            }
            return sb.toString();
        } catch (GroupLookupException e) {
            log.error("describeConsumerGroup failed for group {} in cluster: {}", groupId, clusterName, e);
            return e.getMessage();
        }
    }

    @LlmTool(description = """
            Summarise committed offsets for a consumer group.
            Emits: total lag across all committed partitions, lag by topic, partitions the group
            is assigned but has never committed an offset for, and the single worst-lag partition.
            A partition reported as [NEGATIVE LAG] means the committed offset is ahead of the log
            end — a sign of unclean leader election, topic recreation, or a forward offset reset,
            not a caught-up consumer.
            Parameter clusterName: name of the Kafka cluster.
            Parameter groupId: consumer group ID.
            """)
    public String listConsumerGroupOffsets(String clusterName, String groupId) {
        try {
            GroupInfo info = groupService.getGroupState(groupId, clusterName);
            if (info == null)
                return "Consumer group '" + groupId + "' has no committed offsets and no live assignment in cluster '" + clusterName + "'.";

            Map<String, Long> lagByTopic = new TreeMap<>();
            GroupInfo.PartitionLag worst = null;
            for (GroupInfo.PartitionLag pl : info.partitionLags()) {
                lagByTopic.merge(pl.topic(), Math.max(0, pl.lag()), Long::sum);
                if (worst == null || pl.lag() > worst.lag()) worst = pl;
            }

            StringBuilder sb = new StringBuilder("Consumer group '").append(groupId).append("' offsets summary:\n")
                    .append("  total partitions committed: ").append(info.partitionLags().size()).append("\n")
                    .append("  total lag (sum across committed partitions): ").append(info.totalLag()).append("\n")
                    .append("  lag by topic: ").append(lagByTopic).append("\n")
                    .append("  assigned but never committed: ").append(info.neverCommittedPartitions().size()).append("\n");
            if (!info.neverCommittedPartitions().isEmpty()) {
                sb.append("  never-committed: ").append(String.join(", ", info.neverCommittedPartitions())).append("\n");
            }
            if (worst != null) {
                sb.append("  worst-lag partition: ").append(worst.topic()).append("-").append(worst.partition())
                        .append("  committed=").append(worst.committedOffset())
                        .append("  end=").append(worst.endOffset())
                        .append("  lag=").append(worst.lag());
                if (worst.negativeLag()) {
                    sb.append("  [NEGATIVE LAG — committed offset is ahead of log end; partition may have been truncated or the topic recreated]");
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (GroupLookupException e) {
            log.error("listConsumerGroupOffsets failed for group {} in cluster: {}", groupId, clusterName, e);
            return e.getMessage();
        }
    }

    // -----------------------------------------------------------------------
    // ACLs
    // -----------------------------------------------------------------------

    @LlmTool(description = """
            List all ACLs for a specific principal (e.g. 'User:alice').
            Output is bounded to one principal — always safe to call.
            Parameter clusterName: name of the Kafka cluster.
            Parameter principal: principal in 'User:<name>' format, e.g. 'User:alice'.
            """)
    public String listAclsForPrincipal(String clusterName, String principal) {
        try {
            AclBindingFilter filter = new AclBindingFilter(
                    ResourcePatternFilter.ANY,
                    new AccessControlEntryFilter(principal, null, AclOperation.ANY, AclPermissionType.ANY));
            Collection<AclBinding> acls = admin(clusterName)
                    .describeAcls(filter).values().get(TIMEOUT_SEC, TimeUnit.SECONDS);
            if (acls.isEmpty()) return "No ACLs found for principal '" + principal + "' in cluster '" + clusterName + "'.";
            StringBuilder sb = new StringBuilder("ACLs for principal '").append(principal)
                    .append("' (").append(acls.size()).append("):\n");
            acls.stream()
                    .sorted(Comparator.comparing(a -> a.pattern().name()))
                    .forEach(a -> sb.append("  ")
                            .append(a.pattern().resourceType()).append(":").append(a.pattern().name())
                            .append("  pattern:").append(a.pattern().patternType())
                            .append("  op:").append(a.entry().operation())
                            .append("  permission:").append(a.entry().permissionType())
                            .append("  host:").append(a.entry().host())
                            .append("\n"));
            return sb.toString();
        } catch (Exception e) {
            log.error("listAclsForPrincipal failed for principal {} in cluster: {}", principal, clusterName, e);
            return errorMessage(e);
        }
    }

    @LlmTool(description = "List all ACLs that grant access to a specific topic — including PREFIXED and wildcard ('*') bindings that cover it, not just an exact-name literal match. Output is bounded to one resource. Parameter clusterName: name of the Kafka cluster. Parameter topicName: topic name.")
    public String listAclsForTopic(String clusterName, String topicName) {
        try {
            // PatternType.MATCH resolves PREFIXED and wildcard bindings that actually grant
            // access to topicName; PatternType.ANY only matches an exact-name LITERAL binding
            // and silently misses e.g. a PREFIXED "orders." ACL granting access to "orders.events".
            AclBindingFilter filter = new AclBindingFilter(
                    new ResourcePatternFilter(ResourceType.TOPIC, topicName, PatternType.MATCH),
                    AccessControlEntryFilter.ANY);
            Collection<AclBinding> acls = admin(clusterName)
                    .describeAcls(filter).values().get(TIMEOUT_SEC, TimeUnit.SECONDS);
            if (acls.isEmpty()) return "No ACLs found for topic '" + topicName + "' in cluster '" + clusterName + "'.";
            StringBuilder sb = new StringBuilder("ACLs for topic '").append(topicName)
                    .append("' (").append(acls.size()).append("):\n");
            acls.stream()
                    .sorted(Comparator.comparing(a -> a.entry().principal()))
                    .forEach(a -> sb.append("  principal:").append(a.entry().principal())
                            .append("  pattern:").append(a.pattern().patternType())
                            .append("  op:").append(a.entry().operation())
                            .append("  permission:").append(a.entry().permissionType())
                            .append("  host:").append(a.entry().host())
                            .append("\n"));
            return sb.toString();
        } catch (Exception e) {
            log.error("listAclsForTopic failed for topic {} in cluster: {}", topicName, clusterName, e);
            return errorMessage(e);
        }
    }

    // -----------------------------------------------------------------------
    // Client Quotas
    // -----------------------------------------------------------------------

    @LlmTool(description = "Describe produce/fetch byte-rate quotas configured for a specific user principal. Parameter clusterName: name of the Kafka cluster. Parameter userName: user name (without 'User:' prefix).")
    public String describeClientQuotaByUser(String clusterName, String userName) {
        try {
            ClientQuotaFilter filter = ClientQuotaFilter.containsOnly(
                    List.of(ClientQuotaFilterComponent.ofEntity("user", userName)));
            Map<ClientQuotaEntity, Map<String, Double>> quotas = admin(clusterName)
                    .describeClientQuotas(filter).entities().get(TIMEOUT_SEC, TimeUnit.SECONDS);
            if (quotas.isEmpty()) return "No quotas found for user '" + userName + "' in cluster '" + clusterName + "'.";
            return formatQuotas(quotas);
        } catch (Exception e) {
            log.error("describeClientQuotaByUser failed for user {} in cluster: {}", userName, clusterName, e);
            return errorMessage(e);
        }
    }

    @LlmTool(description = "Describe produce/fetch byte-rate quotas configured for a specific client ID. Parameter clusterName: name of the Kafka cluster. Parameter clientId: client ID.")
    public String describeClientQuotaByClientId(String clusterName, String clientId) {
        try {
            ClientQuotaFilter filter = ClientQuotaFilter.containsOnly(
                    List.of(ClientQuotaFilterComponent.ofEntity("client-id", clientId)));
            Map<ClientQuotaEntity, Map<String, Double>> quotas = admin(clusterName)
                    .describeClientQuotas(filter).entities().get(TIMEOUT_SEC, TimeUnit.SECONDS);
            if (quotas.isEmpty()) return "No quotas found for client-id '" + clientId + "' in cluster '" + clusterName + "'.";
            return formatQuotas(quotas);
        } catch (Exception e) {
            log.error("describeClientQuotaByClientId failed for clientId {} in cluster: {}", clientId, clusterName, e);
            return errorMessage(e);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String errorMessage(Exception e) {
        return AdminClientErrors.describe(e, TIMEOUT_SEC);
    }

    private static final java.util.regex.Pattern SENSITIVE_CONFIG_NAME =
            java.util.regex.Pattern.compile(".*(password|jaas|credential|secret|\\btoken\\b).*", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Drops config entries Kafka itself flags as sensitive, plus common credential-bearing names the broker doesn't always mark (e.g. embedded JAAS config). */
    private static boolean isSensitiveConfig(ConfigEntry e) {
        return e.isSensitive() || SENSITIVE_CONFIG_NAME.matcher(e.name()).matches();
    }

    /** Same regex heuristic used by GraphService for topic/config matching: exact, prefix, or regex. */
    private static boolean matchesPattern(String value, String pattern) {
        if (pattern == null || pattern.isBlank() || pattern.equals("*")) return true;
        if (looksLikeRegex(pattern)) {
            try {
                return java.util.regex.Pattern.compile(pattern).matcher(value).matches();
            } catch (java.util.regex.PatternSyntaxException e) {
                return false;
            }
        }
        return value.equals(pattern) || value.startsWith(pattern);
    }

    private static boolean looksLikeRegex(String s) {
        return s.contains(".*") || s.contains(".+") || s.contains("[")
                || s.contains("(") || s.contains("?") || s.contains("+")
                || s.contains("^") || s.contains("$");
    }

    private static Long tryParseLong(String s) {
        try {
            return s == null ? null : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String nodeIds(List<Node> nodes) {
        return nodes.stream().map(n -> String.valueOf(n.id())).collect(Collectors.joining(",", "[", "]"));
    }

    private String formatQuotas(Map<ClientQuotaEntity, Map<String, Double>> quotas) {
        StringBuilder sb = new StringBuilder();
        quotas.forEach((entity, values) -> {
            sb.append("Entity: ").append(entity.entries()).append("\n");
            values.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append("\n"));
        });
        return sb.toString();
    }
}
