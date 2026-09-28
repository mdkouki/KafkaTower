package com.lynra.kafkatower.service;

import jakarta.annotation.PostConstruct;
import org.apache.kafka.clients.admin.AdminClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Periodically rebuilds the in-memory topic graph for every configured Kafka cluster
 * ({@code kafka.clusters.*}), one {@link TopicGraphBuilder#build} call per cluster. REST reads
 * always come from {@link TopicGraphStore}, never trigger a live Kafka call themselves.
 */
@Component
public class TopicGraphRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(TopicGraphRefreshJob.class);

    private final Map<String, AdminClient> kafkaAdminMap;
    private final TopicGraphBuilder builder;
    private final TopicGraphStore store;

    public TopicGraphRefreshJob(Map<String, AdminClient> kafkaAdminMap, TopicGraphBuilder builder, TopicGraphStore store) {
        this.kafkaAdminMap = kafkaAdminMap;
        this.builder = builder;
        this.store = store;
    }

    @PostConstruct
    void initialBuild() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${kafkatower.registry.topic-graph.refresh-interval-ms:300000}")
    void refresh() {
        Set<String> clusters = kafkaAdminMap.keySet();
        if (clusters.isEmpty()) {
            log.debug("Topic graph refresh: no Kafka clusters configured, skipping.");
            return;
        }
        long start = System.currentTimeMillis();
        for (String clusterName : clusters) {
            store.put(builder.build(clusterName));
        }
        log.info("Topic graph refresh: rebuilt {} cluster(s) in {}ms", clusters.size(), System.currentTimeMillis() - start);
    }
}
