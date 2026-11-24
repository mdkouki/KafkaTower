package com.lynra.kafkatower.service;

import com.lynra.kafkatower.kafka.GroupInfo;
import com.lynra.kafkatower.kafka.GroupLookupException;
import com.lynra.kafkatower.kafka.GroupService;
import com.lynra.kafkatower.model.TopicGroupLag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Live consumer-group lag for one topic, shared by the topics REST endpoint and the registry
 * assistant tools so both answer "who is consuming this topic" the same way.
 */
@Service
public class TopicGroupLagService {

    private static final Logger log = LoggerFactory.getLogger(TopicGroupLagService.class);

    private final GroupService groupService;

    public TopicGroupLagService(GroupService groupService) {
        this.groupService = groupService;
    }

    /**
     * Checks every consumer group in the cluster for a live assignment or committed offset on
     * {@code topic}; groups that can't be looked up are skipped. Sorted by group id.
     */
    public List<TopicGroupLag> groupsForTopic(String clusterName, String topic) {
        return groupService.getAllGroupIds(clusterName).stream()
                .map(id -> groupState(id, clusterName))
                .filter(Objects::nonNull)
                .filter(group -> consumesTopic(group, topic))
                .map(group -> new TopicGroupLag(group.groupId(), group.state(), lagOnTopic(group, topic)))
                .sorted(Comparator.comparing(TopicGroupLag::groupId))
                .toList();
    }

    private boolean consumesTopic(GroupInfo group, String topic) {
        return group.partitionLags().stream().anyMatch(lag -> lag.topic().equals(topic));
    }

    private long lagOnTopic(GroupInfo group, String topic) {
        return group.partitionLags().stream()
                .filter(lag -> lag.topic().equals(topic))
                .mapToLong(lag -> Math.max(0, lag.lag()))
                .sum();
    }

    private GroupInfo groupState(String groupId, String clusterName) {
        try {
            return groupService.getGroupState(groupId, clusterName);
        } catch (GroupLookupException e) {
            log.debug("Skipping group '{}' for topic lag lookup: {}", groupId, e.getMessage());
            return null;
        }
    }
}
