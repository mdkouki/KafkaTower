package com.lynra.kafkatower.service;

import com.lynra.kafkatower.model.ClusterTopicGraph;
import com.lynra.kafkatower.model.TopicDetail;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.acl.AccessControlEntry;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclBindingFilter;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Builds one cluster's {@link ClusterTopicGraph}: every topic's partition/replication metadata,
 * non-default configs, and the principals ACL-allowed to produce to / consume from it. Read-only
 * against the cluster; each call is a fresh snapshot, never cached here (that's
 * {@link TopicGraphStore}'s job).
 */
@Service
public class TopicGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(TopicGraphBuilder.class);
    private static final int TIMEOUT_SEC = 15;
    private static final java.util.regex.Pattern SENSITIVE_CONFIG_NAME =
            java.util.regex.Pattern.compile(".*(password|jaas|credential|secret|\\btoken\\b).*", java.util.regex.Pattern.CASE_INSENSITIVE);

    private final Map<String, AdminClient> kafkaAdminMap;

    public TopicGraphBuilder(Map<String, AdminClient> kafkaAdminMap) {
        this.kafkaAdminMap = kafkaAdminMap;
    }

    public ClusterTopicGraph build(String clusterName) {
        AdminClient admin = kafkaAdminMap.get(clusterName);
        if (admin == null) {
            return ClusterTopicGraph.failed(clusterName, "Unknown cluster: " + clusterName);
        }
        try {
            Set<String> topicNames = admin.listTopics().names().get(TIMEOUT_SEC, TimeUnit.SECONDS);
            if (topicNames.isEmpty()) {
                return new ClusterTopicGraph(clusterName, Map.of(), Instant.now(), null);
            }

            Map<String, TopicDescription> descriptions = admin.describeTopics(topicNames)
                    .allTopicNames().get(TIMEOUT_SEC, TimeUnit.SECONDS);

            List<ConfigResource> resources = topicNames.stream()
                    .map(t -> new ConfigResource(ConfigResource.Type.TOPIC, t))
                    .collect(Collectors.toList());
            Map<ConfigResource, Config> configsByResource = admin.describeConfigs(resources)
                    .all().get(TIMEOUT_SEC, TimeUnit.SECONDS);

            Collection<AclBinding> bindings = admin.describeAcls(AclBindingFilter.ANY)
                    .values().get(TIMEOUT_SEC, TimeUnit.SECONDS);
            List<AclBinding> topicAllowBindings = bindings.stream()
                    .filter(b -> b.pattern().resourceType() == ResourceType.TOPIC)
                    .filter(b -> b.entry().permissionType() == AclPermissionType.ALLOW)
                    .collect(Collectors.toList());

            Map<String, Set<String>> producersByTopic = new HashMap<>();
            Map<String, Set<String>> consumersByTopic = new HashMap<>();
            for (String topic : topicNames) {
                for (AclBinding binding : topicAllowBindings) {
                    if (!coversTopic(binding.pattern(), topic)) continue;
                    AccessControlEntry entry = binding.entry();
                    String principal = entry.principal();
                    if (isReadLike(entry.operation())) {
                        consumersByTopic.computeIfAbsent(topic, k -> new TreeSet<>()).add(principal);
                    }
                    if (isWriteLike(entry.operation())) {
                        producersByTopic.computeIfAbsent(topic, k -> new TreeSet<>()).add(principal);
                    }
                }
            }

            Map<String, TopicDetail> topics = new TreeMap<>();
            for (String topic : topicNames) {
                TopicDescription description = descriptions.get(topic);
                if (description == null) continue;

                int partitionCount = description.partitions().size();
                int replicationFactor = partitionCount == 0 ? 0 : description.partitions().get(0).replicas().size();

                Config config = configsByResource.get(new ConfigResource(ConfigResource.Type.TOPIC, topic));
                Map<String, String> configEntries = new TreeMap<>();
                if (config != null) {
                    config.entries().stream()
                            .filter(e -> e.source() == ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG || !e.isDefault())
                            .filter(e -> !isSensitiveConfig(e))
                            .forEach(e -> configEntries.put(e.name(), e.value()));
                }

                topics.put(topic, new TopicDetail(
                        topic,
                        partitionCount,
                        replicationFactor,
                        description.isInternal(),
                        configEntries,
                        sorted(producersByTopic.get(topic)),
                        sorted(consumersByTopic.get(topic))));
            }
            return new ClusterTopicGraph(clusterName, Map.copyOf(topics), Instant.now(), null);
        } catch (Exception e) {
            log.warn("Topic graph build failed for cluster '{}': {}", clusterName, e.getMessage());
            return ClusterTopicGraph.failed(clusterName, e.getMessage());
        }
    }

    /**
     * Whether an ACL binding's resource pattern grants access to the given topic — a LITERAL
     * binding must match exactly, a PREFIXED binding covers any topic starting with its name.
     */
    private boolean coversTopic(ResourcePattern pattern, String topic) {
        return switch (pattern.patternType()) {
            case LITERAL -> pattern.name().equals(topic);
            case PREFIXED -> topic.startsWith(pattern.name());
            default -> false;
        };
    }

    private boolean isReadLike(AclOperation op) {
        return op == AclOperation.READ || op == AclOperation.ALL;
    }

    private boolean isWriteLike(AclOperation op) {
        return op == AclOperation.WRITE || op == AclOperation.ALL;
    }

    private boolean isSensitiveConfig(ConfigEntry e) {
        return e.isSensitive() || SENSITIVE_CONFIG_NAME.matcher(e.name()).matches();
    }

    private List<String> sorted(Set<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
