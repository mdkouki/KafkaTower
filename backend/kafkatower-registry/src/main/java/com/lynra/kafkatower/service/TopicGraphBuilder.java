package com.lynra.kafkatower.service;

import com.lynra.kafkatower.model.ClusterTopicGraph;
import com.lynra.kafkatower.model.TopicDetail;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.TopicDescription;
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
import java.util.function.Predicate;
import java.util.regex.Pattern;
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
    private static final Pattern SENSITIVE_CONFIG_NAME =
            Pattern.compile(".*(password|jaas|credential|secret|\\btoken\\b).*", Pattern.CASE_INSENSITIVE);

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
            return new ClusterTopicGraph(clusterName, describeTopics(admin), Instant.now(), null);
        } catch (Exception e) {
            log.warn("Topic graph build failed for cluster '{}': {}", clusterName, e.getMessage());
            return ClusterTopicGraph.failed(clusterName, e.getMessage());
        }
    }

    private Map<String, TopicDetail> describeTopics(AdminClient admin) throws Exception {
        Set<String> topicNames = admin.listTopics().names().get(TIMEOUT_SEC, TimeUnit.SECONDS);
        if (topicNames.isEmpty()) {
            return Map.of();
        }

        Map<String, TopicDescription> descriptions = admin.describeTopics(topicNames)
                .allTopicNames().get(TIMEOUT_SEC, TimeUnit.SECONDS);
        Map<ConfigResource, Config> configsByResource = admin.describeConfigs(topicResources(topicNames))
                .all().get(TIMEOUT_SEC, TimeUnit.SECONDS);
        List<AclBinding> topicAllowBindings = topicAllowBindings(admin);
        Map<String, Set<String>> producersByTopic =
                principalsByTopic(topicNames, topicAllowBindings, AclAccess::isWriteLike);
        Map<String, Set<String>> consumersByTopic =
                principalsByTopic(topicNames, topicAllowBindings, AclAccess::isReadLike);

        Map<String, TopicDetail> topics = new TreeMap<>();
        for (String topic : topicNames) {
            TopicDescription description = descriptions.get(topic);
            if (description == null) continue;
            topics.put(topic, new TopicDetail(
                    topic,
                    description.partitions().size(),
                    replicationFactor(description),
                    description.isInternal(),
                    nonDefaultConfigs(configsByResource.get(topicResource(topic))),
                    sorted(producersByTopic.get(topic)),
                    sorted(consumersByTopic.get(topic))));
        }
        return Map.copyOf(topics);
    }

    private ConfigResource topicResource(String topic) {
        return new ConfigResource(ConfigResource.Type.TOPIC, topic);
    }

    private List<ConfigResource> topicResources(Set<String> topicNames) {
        return topicNames.stream().map(this::topicResource).collect(Collectors.toList());
    }

    private List<AclBinding> topicAllowBindings(AdminClient admin) throws Exception {
        Collection<AclBinding> bindings = admin.describeAcls(AclBindingFilter.ANY)
                .values().get(TIMEOUT_SEC, TimeUnit.SECONDS);
        return bindings.stream()
                .filter(b -> b.pattern().resourceType() == ResourceType.TOPIC)
                .filter(b -> b.entry().permissionType() == AclPermissionType.ALLOW)
                .collect(Collectors.toList());
    }

    private Map<String, Set<String>> principalsByTopic(Set<String> topicNames, List<AclBinding> bindings,
                                                       Predicate<AclOperation> grantsAccess) {
        Map<String, Set<String>> principalsByTopic = new HashMap<>();
        for (String topic : topicNames) {
            for (AclBinding binding : bindings) {
                AccessControlEntry entry = binding.entry();
                if (coversTopic(binding.pattern(), topic) && grantsAccess.test(entry.operation())) {
                    principalsByTopic.computeIfAbsent(topic, k -> new TreeSet<>()).add(entry.principal());
                }
            }
        }
        return principalsByTopic;
    }

    private int replicationFactor(TopicDescription description) {
        return description.partitions().isEmpty() ? 0 : description.partitions().get(0).replicas().size();
    }

    private Map<String, String> nonDefaultConfigs(Config config) {
        Map<String, String> configEntries = new TreeMap<>();
        if (config != null) {
            config.entries().stream()
                    .filter(e -> e.source() == ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG || !e.isDefault())
                    .filter(e -> !isSensitiveConfig(e))
                    .forEach(e -> configEntries.put(e.name(), e.value()));
        }
        return configEntries;
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

    private boolean isSensitiveConfig(ConfigEntry e) {
        return e.isSensitive() || SENSITIVE_CONFIG_NAME.matcher(e.name()).matches();
    }

    private List<String> sorted(Set<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
