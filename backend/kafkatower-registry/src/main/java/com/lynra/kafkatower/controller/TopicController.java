package com.lynra.kafkatower.controller;

import com.lynra.kafkatower.model.TopicClusterStatus;
import com.lynra.kafkatower.model.TopicDetail;
import com.lynra.kafkatower.model.TopicGroupLag;
import com.lynra.kafkatower.model.TopicSummaryLite;
import com.lynra.kafkatower.service.TopicGraphStore;
import com.lynra.kafkatower.service.TopicGroupLagService;
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

    private final TopicGraphStore store;
    private final TopicGroupLagService groupLags;

    public TopicController(TopicGraphStore store, TopicGroupLagService groupLags) {
        this.store = store;
        this.groupLags = groupLags;
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

    /** Live consumer groups with a live assignment or committed offset on this topic, with their lag. */
    @GetMapping("/detail/groups")
    public List<TopicGroupLag> groupsForTopic(@RequestParam String clusterName, @RequestParam String topic) {
        return groupLags.groupsForTopic(clusterName, topic);
    }
}
