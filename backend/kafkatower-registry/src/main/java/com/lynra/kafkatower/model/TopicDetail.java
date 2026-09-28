package com.lynra.kafkatower.model;

import java.util.List;
import java.util.Map;

/**
 * Everything known about a single Kafka topic on one cluster: structural metadata, non-default
 * configs, and the principals ACL-allowed to produce to / consume from it (including PREFIXED
 * bindings that cover this topic, not just an exact-name literal one).
 */
public record TopicDetail(
        String name,
        int partitionCount,
        int replicationFactor,
        boolean internal,
        Map<String, String> configs,
        List<String> producers,
        List<String> consumers) {
}
