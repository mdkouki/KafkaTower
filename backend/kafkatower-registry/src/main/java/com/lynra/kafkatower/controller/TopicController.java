package com.lynra.kafkatower.controller;

import com.lynra.kafkatower.kafka.GroupInfo;
import com.lynra.kafkatower.kafka.GroupLookupException;
import com.lynra.kafkatower.kafka.GroupService;
import com.lynra.kafkatower.model.TopicClusterStatus;
import com.lynra.kafkatower.model.TopicDetail;
import com.lynra.kafkatower.model.TopicGroupLag;
import com.lynra.kafkatower.model.TopicSummaryLite;
import com.lynra.kafkatower.service.TopicGraphStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Serves the in-memory topic graph built by {@code TopicGraphRefreshJob} — read-only, no live
 * Kafka calls, except {@link #groupsForTopic} which reports live consumer-group lag on demand
 * since lag is inherently point-in-time.
 */
@RestController
@RequestMapping("/api/topics")
public class TopicController {

    private static final Logger log = LoggerFactory.getLogger(TopicController.class);

    private final TopicGraphStore store;
    private final GroupService groupService;

    public TopicController(TopicGraphStore store, GroupService groupService) {
        this.store = store;
        this.groupService = groupService;
    }

    @GetMapping
    public ResponseEntity<List<TopicSummaryLite>> listTopics(@RequestParam String clusterName) {
        return store.get(clusterName)
                .map(g -> ResponseEntity.ok(
                        g.topicsByName().values().stream()
                                .map(TopicSummaryLite::from)
                                .sorted(Comparator.comparing(TopicSummaryLite::name))
                                .toList()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/detail")
    public ResponseEntity<TopicDetail> getTopic(@RequestParam String clusterName, @RequestParam String topic) {
        return store.get(clusterName)
                .flatMap(g -> Optional.ofNullable(g.topicsByName().get(topic)))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/status")
    public ResponseEntity<TopicClusterStatus> status(@RequestParam String clusterName) {
        return store.get(clusterName)
                .map(g -> ResponseEntity.ok(new TopicClusterStatus(g.clusterName(), g.builtAt(), g.topicsByName().size(), g.error())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Checks every consumer group in the cluster for a live assignment or committed offset on this topic. */
    @GetMapping("/detail/groups")
    public List<TopicGroupLag> groupsForTopic(@RequestParam String clusterName, @RequestParam String topic) {
        return groupService.getAllGroupIds(clusterName).stream()
                .map(id -> safeGetGroupState(id, clusterName))
                .filter(g -> g != null)
                .filter(g -> g.partitionLags().stream().anyMatch(pl -> pl.topic().equals(topic)))
                .map(g -> new TopicGroupLag(
                        g.groupId(),
                        g.state(),
                        g.partitionLags().stream()
                                .filter(pl -> pl.topic().equals(topic))
                                .mapToLong(pl -> Math.max(0, pl.lag()))
                                .sum()))
                .sorted(Comparator.comparing(TopicGroupLag::groupId))
                .toList();
    }

    private GroupInfo safeGetGroupState(String groupId, String clusterName) {
        try {
            return groupService.getGroupState(groupId, clusterName);
        } catch (GroupLookupException e) {
            log.debug("Skipping group '{}' for topic lag lookup: {}", groupId, e.getMessage());
            return null;
        }
    }
}
