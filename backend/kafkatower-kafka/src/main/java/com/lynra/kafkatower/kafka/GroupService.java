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
    private static final int LIST_TIMEOUT_SEC = 5;
    private static final String UNKNOWN_STATE = "UNKNOWN";
    private static final Comparator<TopicPartition> PARTITION_ORDER =
            Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition);
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

            Set<TopicPartition> assigned = description == null ? Set.of() : assignedPartitions(description);
            if (committed.isEmpty() && assigned.isEmpty()) return null;

            List<String> neverCommitted = assigned.stream()
                    .filter(tp -> !committed.containsKey(tp))
                    .sorted(PARTITION_ORDER)
                    .map(GroupService::partitionLabel)
                    .collect(Collectors.toList());
            List<GroupInfo.PartitionLag> lags = partitionLags(committed, fetchEndOffsets(adminClient, committed.keySet()));
            long totalLag = lags.stream().mapToLong(pl -> Math.max(0, pl.lag())).sum();

            if (description == null) {
                return new GroupInfo(group, UNKNOWN_STATE, null, null, List.of(), lags, neverCommitted, totalLag);
            }
            return new GroupInfo(group, description.state().toString(), coordinatorLabel(description.coordinator()),
                    description.partitionAssignor(), memberInfos(description), lags, neverCommitted, totalLag);
        } catch (Exception e) {
            log.error("Failed to fetch group info for: {}", group, e);
            throw new GroupLookupException(AdminClientErrors.describe(e, TIMEOUT_SEC), e);
        }
    }

    private static Set<TopicPartition> assignedPartitions(ConsumerGroupDescription description) {
        return description.members().stream()
                .flatMap(m -> m.assignment().topicPartitions().stream())
                .collect(Collectors.toSet());
    }

    private static String coordinatorLabel(Node node) {
        return node == null ? null : "broker " + node.id() + " (" + node.host() + ":" + node.port() + ")";
    }

    private static List<GroupInfo.MemberInfo> memberInfos(ConsumerGroupDescription description) {
        return description.members().stream()
                .map(m -> new GroupInfo.MemberInfo(
                        m.consumerId(),
                        m.clientId(),
                        m.host(),
                        m.assignment().topicPartitions().stream()
                                .map(GroupService::partitionLabel)
                                .sorted()
                                .collect(Collectors.toList())))
                .collect(Collectors.toList());
    }

    private static String partitionLabel(TopicPartition tp) {
        return tp.topic() + "-" + tp.partition();
    }

    private Map<TopicPartition, Long> fetchEndOffsets(AdminClient adminClient, Set<TopicPartition> partitions)
            throws Exception {
        Map<TopicPartition, Long> endOffsets = new HashMap<>();
        if (partitions.isEmpty()) return endOffsets;
        Map<TopicPartition, OffsetSpec> latestSpecs = partitions.stream()
                .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
        adminClient.listOffsets(latestSpecs)
                .all().get(TIMEOUT_SEC, TimeUnit.SECONDS)
                .forEach((tp, info) -> endOffsets.put(tp, info.offset()));
        return endOffsets;
    }

    // A partition whose end offset couldn't be resolved is excluded rather than reported with a fabricated lag=0.
    private static List<GroupInfo.PartitionLag> partitionLags(Map<TopicPartition, OffsetAndMetadata> committed,
                                                              Map<TopicPartition, Long> endOffsets) {
        return committed.entrySet().stream()
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
    }

    public Set<String> getSubscribedTopics(String groupId, String clusterName) {
        try {
            AdminClient adminClient = kafkaAdminMap.get(clusterName);
            if (adminClient == null) {
                log.warn("No AdminClient configured for cluster '{}' — skipping topic lookup for group '{}'", clusterName, groupId);
                return Collections.emptySet();
            }
            DescribeConsumerGroupsResult result = adminClient.describeConsumerGroups(Collections.singleton(groupId));
            ConsumerGroupDescription description = result.all().get(LIST_TIMEOUT_SEC, TimeUnit.SECONDS).get(groupId);
            return assignedPartitions(description).stream()
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
                    .get(LIST_TIMEOUT_SEC, TimeUnit.SECONDS)
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
                    .get(LIST_TIMEOUT_SEC, TimeUnit.SECONDS)
                    .stream()
                    .map(g -> new GroupSummary(g.groupId(), g.state().map(Object::toString).orElse(UNKNOWN_STATE)))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to list consumer groups with state for cluster: {}", clusterName, e);
            return Collections.emptyList();
        }
    }
}
