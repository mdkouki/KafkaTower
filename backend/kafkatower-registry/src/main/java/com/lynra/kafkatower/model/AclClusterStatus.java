package com.lynra.kafkatower.model;

import java.time.Instant;

/** Freshness/health of one cluster's in-memory ACL graph, for the UI to display. */
public record AclClusterStatus(
        String clusterName,
        Instant builtAt,
        int userCount,
        String error) {
}
