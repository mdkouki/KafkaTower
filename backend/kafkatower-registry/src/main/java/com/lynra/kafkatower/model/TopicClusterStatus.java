package com.lynra.kafkatower.model;

import java.time.Instant;

/** Freshness/health of one cluster's in-memory topic graph, for the UI to display. */
public record TopicClusterStatus(
        String clusterName,
        Instant builtAt,
        int topicCount,
        String error) {
}
