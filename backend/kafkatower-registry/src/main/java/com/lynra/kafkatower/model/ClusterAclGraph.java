package com.lynra.kafkatower.model;

import java.time.Instant;
import java.util.Map;

/** One cluster's full ACL-derived, per-principal graph, held entirely in memory. */
public record ClusterAclGraph(
        String clusterName,
        Map<String, UserAclSummary> usersByPrincipal,
        Instant builtAt,
        String error) {

    public static ClusterAclGraph failed(String clusterName, String error) {
        return new ClusterAclGraph(clusterName, Map.of(), Instant.now(), error);
    }

    public boolean isHealthy() {
        return error == null;
    }
}
