package com.lynra.kafkatower.model;

import java.util.List;

/**
 * Everything a single Kafka principal is ACL-allowed to do on one cluster, aggregated from live
 * {@code ALLOW} ACL bindings. Resource names ending in {@code *} were granted via a PREFIXED
 * binding rather than an exact-name LITERAL one.
 */
public record UserAclSummary(
        String principal,
        List<String> consumeTopics,
        List<String> produceTopics,
        List<AclGroupGrant> consumerGroups,
        List<String> transactionalIds) {
}
