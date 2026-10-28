package com.lynra.kafkatower.model;

import java.util.List;

/** OpenSearch connections saved from the admin console: one per Kafka cluster. */
public record OpenSearchSettings(boolean enabled, String defaultCluster, List<Cluster> clusters) {

    public static final String SECTION = "opensearch";

    public OpenSearchSettings {
        clusters = clusters == null ? List.of() : List.copyOf(clusters);
    }

    public record Cluster(String name, String url, String username, String password, String index) {}
}
