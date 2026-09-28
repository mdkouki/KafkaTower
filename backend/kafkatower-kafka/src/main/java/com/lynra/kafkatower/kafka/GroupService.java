package com.lynra.kafkatower.kafka;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Canonical consumer-group state + lag lookup. This is the single implementation behind the
 * /api/groups REST endpoint and the Kafka Admin Client tools' consumer-group tools — both now
 * report the same numbers for the same group instead of each recomputing lag independently.
 */
@Service
public class GroupService {

    private static final Logger log = LoggerFactory.getLogger(GroupService.class);
    private static final int TIMEOUT_SEC = 10;
    private final Map<String, AdminClient> kafkaAdminMap;

    public GroupService(Map<String, AdminClient> kafkaAdminMap) {
        this.kafkaAdminMap = kafkaAdminMap;
    }

    /**
     * Returns null when the group genuinely has nothing to report (unknown cluster, or no
     * committed offsets and no live assignment). Any other AdminClient failure (timeout,
     * unreachable broker, ...) is raised as {@link GroupLookupException} rather than swallowed,
     * so callers can surface what actually went wrong instead of a misleading "not found".
     */
    public GroupInfo getGroupState(String group, String cluster) {
        AdminClient adminClient = kafkaAdminMap.get(cluster);
        if (adminClient == null) {
            log.warn("No AdminClient configured for cluster '{}' — available: {}", cluster, kafkaAdminMap.keySet());
            return null;
        }

        try {
            // Independent calls — kick both off before blocking on either.
            KafkaFuture<Map<String, ConsumerGroupDescription>> describeFuture =
                    adminClient.describeConsumerGroups(List.of(group)).all();
            KafkaFuture<Map<TopicPartition, OffsetAndMetadata>> offsetsFuture =
                    adminClient.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata();

            ConsumerGroupDescription description = describeFuture.get(TIMEOUT_SEC, TimeUnit.SECONDS).get(group);
            Map<TopicPartition, OffsetAndMetadata> committed = offsetsFuture.get(TIMEOUT_SEC, TimeUnit.SECONDS);

            String state = "UNKNOWN";
            String coordinator = null;
            String assignor = null;
            Set<TopicPartition> assigned = Set.of();
            List<GroupInfo.MemberInfo> members = List.of();
            if (description != null) {
                state = description.state().toString();
                assignor = description.partitionAssignor();
                if (description.coordinator() != null) {
                    Node node = description.coordinator();
                    coordinator = "broker " + node.id() + " (" + node.host() + ":" + node.port() + ")";
                }
                assigned = description.members().stream()
                        .flatMap(m -> m.assignment().topicPartitions().stream())
                        .collect(Collectors.toSet());
                members = description.members().stream()
                        .map(m -> new GroupInfo.MemberInfo(
                                m.consumerId(),
                                m.clientId(),
                                m.host(),
                                m.assignment().topicPartitions().stream()
                                        .map(tp -> tp.topic() + "-" + tp.partition())
                                        .sorted()
                                        .collect(Collectors.toList())))
                        .collect(Collectors.toList());
            }

            if (committed.isEmpty() && assigned.isEmpty()) return null;

            List<String> neverCommitted = assigned.stream()
                    .filter(tp -> !committed.containsKey(tp))
                    .sorted(Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition))
                    .map(tp -> tp.topic() + "-" + tp.partition())
                    .collect(Collectors.toList());

            Map<TopicPartition, Long> endOffsets = new HashMap<>();
            if (!committed.isEmpty()) {
                Map<TopicPartition, OffsetSpec> offsetSpecMap = committed.keySet().stream()
                        .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
                adminClient.listOffsets(offsetSpecMap)
                        .all().get(TIMEOUT_SEC, TimeUnit.SECONDS)
                        .forEach((tp, info) -> endOffsets.put(tp, info.offset()));
            }

            // A partition whose end offset couldn't be resolved is excluded rather than
            // reported with a fabricated lag=0.
            List<GroupInfo.PartitionLag> lags = committed.entrySet().stream()
                    .filter(e -> endOffsets.containsKey(e.getKey()))
                    .map(e -> {
                        TopicPartition tp = e.getKey();
                        long committedOffset = e.getValue().offset();
                        long endOffset = endOffsets.get(tp);
                        return new GroupInfo.PartitionLag(tp.topic(), tp.partition(), committedOffset, endOffset, endOffset - committedOffset);
                    })
                    .sorted(Comparator.comparing(GroupInfo.PartitionLag::topic)
                            .thenComparingInt(GroupInfo.PartitionLag::partition))
                    .collect(Collectors.toList());

            long totalLag = lags.stream().mapToLong(pl -> Math.max(0, pl.lag())).sum();

            return new GroupInfo(group, state, coordinator, assignor, members, lags, neverCommitted, totalLag);

        } catch (Exception e) {
            log.error("Failed to fetch group info for: {}", group, e);
            throw new GroupLookupException(AdminClientErrors.describe(e, TIMEOUT_SEC), e);
        }
    }

    public Set<String> getSubscribedTopics(String groupId, String clusterName) {
        try {
            AdminClient adminClient = kafkaAdminMap.get(clusterName);
            if (adminClient == null) {
                log.warn("No AdminClient configured for cluster '{}' — skipping topic lookup for group '{}'", clusterName, groupId);
                return Collections.emptySet();
            }
            DescribeConsumerGroupsResult result = adminClient.describeConsumerGroups(Collections.singleton(groupId));
            ConsumerGroupDescription description = result.all().get(5, TimeUnit.SECONDS).get(groupId);
            // Extract topic names from all members' assignments
            return description.members().stream()
                    .flatMap(member -> member.assignment().topicPartitions().stream())
                    .map(TopicPartition::topic)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.error("Failed to fetch group info for: " + groupId, e);
            return Collections.emptySet();
        }
    }

    @Cacheable(value = "consumer-groups", key = "#clusterName")
    public List<String> getAllGroupIds(String clusterName) {
        try {
            return kafkaAdminMap.get(clusterName)
                    .listConsumerGroups()
                    .all()
                    .get(5, TimeUnit.SECONDS)
                    .stream()
                    .map(ConsumerGroupListing::groupId)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to list consumer groups for cluster: {}", clusterName, e);
            return Collections.emptyList();
        }
    }

    public List<String> getGroupsByPrefix(String groupNamePrefix, String clusterName) {
        return getAllGroupIds(clusterName).stream()
                .filter(id -> id.startsWith(groupNamePrefix))
                .collect(Collectors.toList());
    }

    /** Group ID plus its current state, from one cheap {@code listConsumerGroups} call — no per-group lag calculation. */
    public record GroupSummary(String groupId, String state) {
    }

    /**
     * Every real consumer group in the cluster with its current state — a live snapshot, not
     * cached, since state changes over time and callers (the ACL registry's "which groups
     * actually exist for this grant" view) need it fresh rather than up-to-5-minutes-stale.
     */
    public List<GroupSummary> listGroupsWithState(String clusterName) {
        AdminClient adminClient = kafkaAdminMap.get(clusterName);
        if (adminClient == null) return Collections.emptyList();
        try {
            return adminClient.listConsumerGroups()
                    .all()
                    .get(5, TimeUnit.SECONDS)
                    .stream()
                    .map(g -> new GroupSummary(g.groupId(), g.state().map(Object::toString).orElse("UNKNOWN")))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to list consumer groups with state for cluster: {}", clusterName, e);
            return Collections.emptyList();
        }
    }
}
