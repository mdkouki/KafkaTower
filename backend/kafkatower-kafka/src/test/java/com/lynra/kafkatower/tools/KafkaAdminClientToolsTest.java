package com.lynra.kafkatower.tools;

import com.lynra.kafkatower.kafka.GroupService;
import com.lynra.kafkatower.testsupport.AdminClientTestSupport;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.Node;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.lynra.kafkatower.testsupport.AdminClientTestSupport.completed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaAdminClientToolsTest {

    @Mock
    private AdminClient adminClient;

    private KafkaAdminClientTools tools;

    @BeforeEach
    void setUp() {
        Map<String, AdminClient> kafkaAdminMap = new HashMap<>();
        kafkaAdminMap.put("test-cluster", adminClient);
        tools = new KafkaAdminClientTools(kafkaAdminMap, new GroupService(kafkaAdminMap));
    }

    @Test
    void listBrokersReturnsUnknownClusterError() {
        String result = tools.listBrokers("nonexistent-cluster");

        assertThat(result).startsWith("Error").contains("Unknown cluster: nonexistent-cluster");
    }

    @Test
    void listBrokersFormatsNodesAndMarksController() {
        Node broker0 = new Node(0, "host0", 9092);
        Node broker1 = new Node(1, "host1", 9092);
        DescribeClusterResult result = mock(DescribeClusterResult.class);
        when(result.nodes()).thenReturn(completed(List.of(broker0, broker1)));
        when(result.controller()).thenReturn(completed(broker1));
        when(adminClient.describeCluster()).thenReturn(result);

        String output = tools.listBrokers("test-cluster");

        assertThat(output).contains("Brokers in cluster 'test-cluster' (2):");
        assertThat(output).contains("broker 0  host0:9092");
        assertThat(output).contains("broker 1  host1:9092  [controller]");
    }

    @Test
    void listBrokersReturnsErrorMessageWhenAdminClientThrows() {
        when(adminClient.describeCluster()).thenThrow(new RuntimeException("connection refused"));

        String output = tools.listBrokers("test-cluster");

        assertThat(output).isEqualTo("Error during operation: RuntimeException: connection refused");
    }

    @Test
    void describeClusterFormatsClusterIdAndController() {
        Node controller = new Node(0, "host0", 9092);
        DescribeClusterResult result = mock(DescribeClusterResult.class);
        when(result.clusterId()).thenReturn(completed("cluster-abc"));
        when(result.controller()).thenReturn(completed(controller));
        when(result.nodes()).thenReturn(completed(List.of(controller)));
        when(adminClient.describeCluster()).thenReturn(result);

        String output = tools.describeCluster("test-cluster");

        assertThat(output).contains("cluster-id: cluster-abc");
        assertThat(output).contains("controller: broker 0 (host0:9092)");
        assertThat(output).contains("broker count: 1");
    }

    @Test
    void listTopicsFiltersByPrefixAndSortsResults() throws Exception {
        ListTopicsResult result = mock(ListTopicsResult.class);
        when(result.names()).thenReturn(completed(Set.of("orders.created", "orders.cancelled", "payments.settled")));
        when(adminClient.listTopics()).thenReturn(result);

        String output = tools.listTopics("test-cluster", "orders.");

        assertThat(output).contains("(2):");
        assertThat(output).contains("orders.cancelled");
        assertThat(output).contains("orders.created");
        assertThat(output).doesNotContain("payments.settled");
    }

    @Test
    void listTopicsReturnsNoTopicsMessageWhenNothingMatches() throws Exception {
        ListTopicsResult result = mock(ListTopicsResult.class);
        when(result.names()).thenReturn(completed(Set.of("payments.settled")));
        when(adminClient.listTopics()).thenReturn(result);

        String output = tools.listTopics("test-cluster", "orders.");

        assertThat(output).contains("No topics found matching prefix 'orders.'");
    }

    @Test
    void listTopicsRejectsBlankPrefixWithoutQueryingTheCluster() {
        String output = tools.listTopics("test-cluster", "");

        assertThat(output).contains("non-empty prefix is required");
        verifyNoInteractions(adminClient);
    }

    @Test
    void describeTopicReturnsNotFoundMessageWhenTopicIsMissing() throws Exception {
        DescribeTopicsResult result = mock(DescribeTopicsResult.class);
        when(result.allTopicNames()).thenReturn(completed(Map.of()));
        when(adminClient.describeTopics(List.of("missing-topic"))).thenReturn(result);

        String output = tools.describeTopic("test-cluster", "missing-topic");

        assertThat(output).isEqualTo("Topic 'missing-topic' not found in cluster 'test-cluster'.");
    }

    @Test
    void describeConsumerGroupReturnsNotFoundMessageWhenGroupIsMissing() throws Exception {
        AdminClientTestSupport.stubGroupNotFound(adminClient, "missing-group");

        String output = tools.describeConsumerGroup("test-cluster", "missing-group");

        assertThat(output).isEqualTo("Consumer group 'missing-group' not found in cluster 'test-cluster'.");
    }

    @Test
    void listConsumerGroupsReturnsUnknownClusterError() {
        String output = tools.listConsumerGroups("nonexistent-cluster", "test");

        assertThat(output).startsWith("Error").contains("Unknown cluster");
    }

    @Test
    void listConsumerGroupsRejectsBlankPrefixWithoutQueryingTheCluster() {
        String output = tools.listConsumerGroups("nonexistent-cluster", "");

        assertThat(output).contains("non-empty prefix is required").doesNotContain("Unknown cluster");
    }
}
