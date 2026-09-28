package com.lynra.kafkatower.model;

import java.time.Instant;
import java.util.Map;

/** One cluster's full topic graph — metadata, configs, and ACLs for every topic — held entirely in memory. */
public record ClusterTopicGraph(
        String clusterName,
        Map<String, TopicDetail> topicsByName,
        Instant builtAt,
        String error) {

    public static ClusterTopicGraph failed(String clusterName, String error) {
        return new ClusterTopicGraph(clusterName, Map.of(), Instant.now(), error);
    }

    public boolean isHealthy() {
        return error == null;
    }
}
