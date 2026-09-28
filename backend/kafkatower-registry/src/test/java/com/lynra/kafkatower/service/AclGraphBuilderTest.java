package com.lynra.kafkatower.service;

import com.lynra.kafkatower.model.AclGroupGrant;
import com.lynra.kafkatower.model.ClusterAclGraph;
import com.lynra.kafkatower.model.UserAclSummary;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeAclsResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.acl.AccessControlEntry;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AclGraphBuilderTest {

    @Mock
    private AdminClient adminClient;

    private Map<String, AdminClient> kafkaAdminMap;
    private AclGraphBuilder builder;

    @BeforeEach
    void setUp() {
        kafkaAdminMap = new HashMap<>();
        kafkaAdminMap.put("test-cluster", adminClient);
        builder = new AclGraphBuilder(kafkaAdminMap);
    }

    private static <T> KafkaFuture<T> completed(T value) {
        KafkaFutureImpl<T> future = new KafkaFutureImpl<>();
        future.complete(value);
        return future;
    }

    private static AclBinding allow(ResourceType type, String name, PatternType patternType,
                                     String principal, AclOperation op) {
        return new AclBinding(
                new ResourcePattern(type, name, patternType),
                new AccessControlEntry(principal, "*", op, AclPermissionType.ALLOW));
    }

    @Test
    void groupsAllowedTopicGroupAndTransactionalIdAclsByPrincipal() throws Exception {
        List<AclBinding> bindings = List.of(
                allow(ResourceType.TOPIC, "orders.events", PatternType.LITERAL, "User:alice", AclOperation.READ),
                allow(ResourceType.TOPIC, "orders.audit", PatternType.LITERAL, "User:alice", AclOperation.WRITE),
                allow(ResourceType.TOPIC, "orders.", PatternType.PREFIXED, "User:alice", AclOperation.READ),
                allow(ResourceType.GROUP, "orders-consumer", PatternType.LITERAL, "User:alice", AclOperation.READ),
                allow(ResourceType.GROUP, "orders-", PatternType.PREFIXED, "User:alice", AclOperation.READ),
                allow(ResourceType.TRANSACTIONAL_ID, "orders-txn", PatternType.LITERAL, "User:alice", AclOperation.WRITE),
                allow(ResourceType.TOPIC, "billing.events", PatternType.LITERAL, "User:bob", AclOperation.ALL));

        DescribeAclsResult result = mock(DescribeAclsResult.class);
        when(result.values()).thenReturn(completed(bindings));
        when(adminClient.describeAcls(org.mockito.ArgumentMatchers.any())).thenReturn(result);

        ClusterAclGraph graph = builder.build("test-cluster");

        assertTrue(graph.isHealthy());
        assertEquals("test-cluster", graph.clusterName());
        assertEquals(2, graph.usersByPrincipal().size());

        UserAclSummary alice = graph.usersByPrincipal().get("User:alice");
        assertEquals(List.of("orders.*", "orders.events"), alice.consumeTopics());
        assertEquals(List.of("orders.audit"), alice.produceTopics());
        assertEquals(
                List.of(new AclGroupGrant("orders-", true), new AclGroupGrant("orders-consumer", false)),
                alice.consumerGroups());
        assertEquals(List.of("orders-txn"), alice.transactionalIds());

        UserAclSummary bob = graph.usersByPrincipal().get("User:bob");
        assertEquals(List.of("billing.events"), bob.consumeTopics());
        assertEquals(List.of("billing.events"), bob.produceTopics());
        assertTrue(bob.consumerGroups().isEmpty());

        assertTrue(bob.transactionalIds().isEmpty());
    }

    @Test
    void ignoresDenyBindingsAndUnrelatedResourceTypes() throws Exception {
        List<AclBinding> bindings = List.of(
                new AclBinding(
                        new ResourcePattern(ResourceType.TOPIC, "secret.topic", PatternType.LITERAL),
                        new AccessControlEntry("User:eve", "*", AclOperation.READ, AclPermissionType.DENY)),
                allow(ResourceType.CLUSTER, "kafka-cluster", PatternType.LITERAL, "User:admin", AclOperation.ALL));

        DescribeAclsResult result = mock(DescribeAclsResult.class);
        when(result.values()).thenReturn(completed(bindings));
        when(adminClient.describeAcls(org.mockito.ArgumentMatchers.any())).thenReturn(result);

        ClusterAclGraph graph = builder.build("test-cluster");

        assertTrue(graph.isHealthy());
        assertTrue(graph.usersByPrincipal().isEmpty());
    }

    @Test
    void returnsFailedGraphForUnknownCluster() {
        ClusterAclGraph graph = builder.build("missing-cluster");

        assertFalse(graph.isHealthy());
        assertEquals("missing-cluster", graph.clusterName());
        assertTrue(graph.usersByPrincipal().isEmpty());
    }

    @Test
    void returnsFailedGraphWhenAdminClientThrows() {
        when(adminClient.describeAcls(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("boom"));

        ClusterAclGraph graph = builder.build("test-cluster");

        assertFalse(graph.isHealthy());
        assertEquals("boom", graph.error());
    }
}
