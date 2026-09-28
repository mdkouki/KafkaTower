package com.lynra.kafkatower.service;

import com.lynra.kafkatower.model.AclGroupGrant;
import com.lynra.kafkatower.model.ClusterAclGraph;
import com.lynra.kafkatower.model.UserAclSummary;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.acl.AccessControlEntry;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclBindingFilter;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Builds one cluster's {@link ClusterAclGraph} by describing every live ACL binding and grouping
 * ALLOW entries by principal. Read-only against the cluster; each call is a fresh snapshot, never
 * cached here (that's {@link AclGraphStore}'s job).
 */
@Service
public class AclGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(AclGraphBuilder.class);
    private static final int TIMEOUT_SEC = 15;
    private static final java.util.Comparator<AclGroupGrant> GROUP_GRANT_ORDER =
            java.util.Comparator.comparing(AclGroupGrant::pattern).thenComparing(AclGroupGrant::prefixed);

    private final Map<String, AdminClient> kafkaAdminMap;

    public AclGraphBuilder(Map<String, AdminClient> kafkaAdminMap) {
        this.kafkaAdminMap = kafkaAdminMap;
    }

    public ClusterAclGraph build(String clusterName) {
        AdminClient admin = kafkaAdminMap.get(clusterName);
        if (admin == null) {
            return ClusterAclGraph.failed(clusterName, "Unknown cluster: " + clusterName);
        }
        try {
            Collection<AclBinding> bindings = admin.describeAcls(AclBindingFilter.ANY)
                    .values().get(TIMEOUT_SEC, TimeUnit.SECONDS);

            Map<String, Set<String>> consumeTopics = new TreeMap<>();
            Map<String, Set<String>> produceTopics = new TreeMap<>();
            Map<String, Set<AclGroupGrant>> consumerGroups = new TreeMap<>();
            Map<String, Set<String>> transactionalIds = new TreeMap<>();
            Set<String> principals = new TreeSet<>();

            for (AclBinding binding : bindings) {
                AccessControlEntry entry = binding.entry();
                if (entry.permissionType() != AclPermissionType.ALLOW) continue;

                String principal = entry.principal();
                String resourceName = displayName(binding.pattern());
                AclOperation op = entry.operation();

                switch (binding.pattern().resourceType()) {
                    case TOPIC -> {
                        boolean matched = false;
                        if (isReadLike(op)) {
                            consumeTopics.computeIfAbsent(principal, k -> new TreeSet<>()).add(resourceName);
                            matched = true;
                        }
                        if (isWriteLike(op)) {
                            produceTopics.computeIfAbsent(principal, k -> new TreeSet<>()).add(resourceName);
                            matched = true;
                        }
                        if (matched) principals.add(principal);
                    }
                    case GROUP -> {
                        boolean prefixed = binding.pattern().patternType() == PatternType.PREFIXED;
                        consumerGroups.computeIfAbsent(principal, k -> new TreeSet<>(GROUP_GRANT_ORDER))
                                .add(new AclGroupGrant(binding.pattern().name(), prefixed));
                        principals.add(principal);
                    }
                    case TRANSACTIONAL_ID -> {
                        transactionalIds.computeIfAbsent(principal, k -> new TreeSet<>()).add(resourceName);
                        principals.add(principal);
                    }
                    default -> {
                        // CLUSTER, DELEGATION_TOKEN, etc. aren't part of the per-user summary.
                    }
                }
            }

            Map<String, UserAclSummary> users = new TreeMap<>();
            for (String principal : principals) {
                users.put(principal, new UserAclSummary(
                        principal,
                        sorted(consumeTopics.get(principal)),
                        sorted(produceTopics.get(principal)),
                        consumerGroups.containsKey(principal) ? List.copyOf(consumerGroups.get(principal)) : List.of(),
                        sorted(transactionalIds.get(principal))));
            }
            return new ClusterAclGraph(clusterName, Map.copyOf(users), Instant.now(), null);
        } catch (Exception e) {
            log.warn("ACL graph build failed for cluster '{}': {}", clusterName, e.getMessage());
            return ClusterAclGraph.failed(clusterName, e.getMessage());
        }
    }

    private boolean isReadLike(AclOperation op) {
        return op == AclOperation.READ || op == AclOperation.ALL;
    }

    private boolean isWriteLike(AclOperation op) {
        return op == AclOperation.WRITE || op == AclOperation.ALL;
    }

    /** PREFIXED bindings are shown with a trailing '*' so the UI can tell them apart from a LITERAL name. */
    private String displayName(ResourcePattern pattern) {
        return pattern.patternType() == PatternType.PREFIXED ? pattern.name() + "*" : pattern.name();
    }

    private List<String> sorted(Set<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
