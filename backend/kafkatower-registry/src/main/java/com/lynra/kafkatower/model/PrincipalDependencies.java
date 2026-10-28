package com.lynra.kafkatower.model;

import java.util.List;

/**
 * Direct (depth-1) topic-based neighbours of one principal. {@code upstream} principals produce to
 * topics the principal consumes; {@code downstream} principals consume topics the principal produces.
 */
public record PrincipalDependencies(
        String principal,
        List<Related> upstream,
        List<Related> downstream) {

    /** A related principal and the topics linking it, as ACL names (PREFIXED ones end in {@code *}). */
    public record Related(String principal, List<String> topics) {
    }
}
