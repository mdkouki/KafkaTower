package com.lynra.kafkatower.service;

import com.lynra.kafkatower.model.ClusterTopicGraph;
import com.lynra.kafkatower.model.TopicDetail;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.acl.AccessControlEntry;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TopicGraphBuilderTest {

    @Mock
    private AdminClient adminClient;

    private Map<String, AdminClient> kafkaAdminMap;
    private TopicGraphBuilder builder;

    @BeforeEach
    void setUp() {
        kafkaAdminMap = new HashMap<>();
        kafkaAdminMap.put("test-cluster", adminClient);
        builder = new TopicGraphBuilder(kafkaAdminMap);
    }

    private static <T> KafkaFuture<T> completed(T value) {
        KafkaFutureImpl<T> future = new KafkaFutureImpl<>();
        future.complete(value);
        return future;
    }

    private static AclBinding allow(String topicName, PatternType patternType, String principal, AclOperation op) {
        return new AclBinding(
                new ResourcePattern(ResourceType.TOPIC, topicName, patternType),
                new AccessControlEntry(principal, "*", op, AclPermissionType.ALLOW));
    }

    private void stubTopics(String... names) {
        ListTopicsResult listResult = mock(ListTopicsResult.class);
        when(listResult.names()).thenReturn(completed(java.util.Set.of(names)));
        when(adminClient.listTopics()).thenReturn(listResult);
    }

    private void stubDescriptions(Map<String, TopicDescription> descriptions) {
        DescribeTopicsResult describeResult = mock(DescribeTopicsResult.class);
        when(describeResult.allTopicNames()).thenReturn(completed(descriptions));
        when(adminClient.describeTopics(any(java.util.Collection.class))).thenReturn(describeResult);
    }

    private void stubConfigs(Map<ConfigResource, Config> configs) {
        DescribeConfigsResult configsResult = mock(DescribeConfigsResult.class);
        when(configsResult.all()).thenReturn(completed(configs));
        when(adminClient.describeConfigs(any(java.util.Collection.class))).thenReturn(configsResult);
    }

    private void stubAcls(List<AclBinding> bindings) {
        DescribeAclsResult aclsResult = mock(DescribeAclsResult.class);
        when(aclsResult.values()).thenReturn(completed(bindings));
        when(adminClient.describeAcls(any())).thenReturn(aclsResult);
    }

    private TopicDescription topicDescription(String name, int partitions, int replicas) {
        Node node = new Node(1, "broker1", 9092);
        List<TopicPartitionInfo> partitionInfos = new java.util.ArrayList<>();
        for (int i = 0; i < partitions; i++) {
            List<Node> replicaNodes = java.util.Collections.nCopies(replicas, node);
            partitionInfos.add(new TopicPartitionInfo(i, node, replicaNodes, replicaNodes));
        }
        return new TopicDescription(name, false, partitionInfos);
    }

    @Test
    void buildsTopicDetailWithMetadataConfigsAndAcls() throws Exception {
        stubTopics("orders.events");
        stubDescriptions(Map.of("orders.events", topicDescription("orders.events", 3, 2)));

        ConfigEntry retentionEntry = new ConfigEntry("retention.ms", "86400000",
                ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG, false, false, List.of(), null, null);
        Config config = new Config(List.of(retentionEntry));
        stubConfigs(Map.of(new ConfigResource(ConfigResource.Type.TOPIC, "orders.events"), config));

        stubAcls(List.of(
                allow("orders.events", PatternType.LITERAL, "User:alice", AclOperation.READ),
                allow("orders.", PatternType.PREFIXED, "User:bob", AclOperation.WRITE)));

        ClusterTopicGraph graph = builder.build("test-cluster");

        assertTrue(graph.isHealthy());
        assertEquals(1, graph.topicsByName().size());
        TopicDetail detail = graph.topicsByName().get("orders.events");
        assertEquals(3, detail.partitionCount());
        assertEquals(2, detail.replicationFactor());
        assertEquals(Map.of("retention.ms", "86400000"), detail.configs());
        assertEquals(List.of("User:alice"), detail.consumers());
        assertEquals(List.of("User:bob"), detail.producers());
    }

    @Test
    void returnsFailedGraphForUnknownCluster() {
        ClusterTopicGraph graph = builder.build("missing-cluster");

        assertFalse(graph.isHealthy());
        assertEquals("missing-cluster", graph.clusterName());
        assertTrue(graph.topicsByName().isEmpty());
    }

    @Test
    void returnsFailedGraphWhenAdminClientThrows() {
        when(adminClient.listTopics()).thenThrow(new RuntimeException("boom"));

        ClusterTopicGraph graph = builder.build("test-cluster");

        assertFalse(graph.isHealthy());
        assertEquals("boom", graph.error());
    }
}
