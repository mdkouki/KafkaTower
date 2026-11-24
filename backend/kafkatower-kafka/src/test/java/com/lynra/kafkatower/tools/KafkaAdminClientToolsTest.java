package com.lynra.kafkatower.tools;

import com.lynra.kafkatower.collections.CollectionPresenter;
import com.lynra.kafkatower.collections.CollectionStore;
import com.lynra.kafkatower.kafka.GroupService;
import com.lynra.kafkatower.testsupport.AdminClientTestSupport;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.Node;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigResource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.lynra.kafkatower.testsupport.AdminClientTestSupport.completed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaAdminClientToolsTest {

    @Mock
    private AdminClient adminClient;

    private static final Pattern COLLECTION_KEY = Pattern.compile("[Cc]ollection key: ([0-9a-f]+)");

    private CollectionStore store;
    private KafkaAdminClientTools tools;

    @BeforeEach
    void setUp() {
        Map<String, AdminClient> kafkaAdminMap = new HashMap<>();
        kafkaAdminMap.put("test-cluster", adminClient);
        store = new CollectionStore(600, 200_000);
        tools = new KafkaAdminClientTools(kafkaAdminMap, new GroupService(kafkaAdminMap), new CollectionPresenter(store, 5));
    }

    private void stubDescribeTopic(String topicName, TopicDescription description) {
        DescribeTopicsResult result = mock(DescribeTopicsResult.class);
        when(result.allTopicNames()).thenReturn(completed(Map.of(topicName, description)));
        when(adminClient.describeTopics(List.of(topicName))).thenReturn(result);
    }

    private static String collectionKey(String output) {
        Matcher matcher = COLLECTION_KEY.matcher(output);
        assertThat(matcher.find()).as("collection key in: " + output).isTrue();
        return matcher.group(1);
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
    void listTopicsFiltersByRegexAndSortsResults() throws Exception {
        ListTopicsResult result = mock(ListTopicsResult.class);
        when(result.names()).thenReturn(completed(Set.of("orders.created", "orders.cancelled", "payments.settled")));
        when(adminClient.listTopics()).thenReturn(result);

        String output = tools.listTopics("test-cluster", "^orders\\.");

        assertThat(output).contains("(2):");
        assertThat(output).contains("orders.cancelled");
        assertThat(output).contains("orders.created");
        assertThat(output).doesNotContain("payments.settled");
        assertThat(output.indexOf("orders.cancelled")).isLessThan(output.indexOf("orders.created"));
    }

    @Test
    void listTopicsRegexCoversSuffixAndAlternationNotJustPrefixes() throws Exception {
        ListTopicsResult result = mock(ListTopicsResult.class);
        when(result.names()).thenReturn(completed(Set.of("orders.created", "orders.dlq", "payments.dlq", "payments.settled")));
        when(adminClient.listTopics()).thenReturn(result);

        String suffix = tools.listTopics("test-cluster", "\\.dlq$");
        assertThat(suffix).contains("(2):").contains("orders.dlq").contains("payments.dlq").doesNotContain("settled");

        String alternation = tools.listTopics("test-cluster", "^(orders|payments)\\.(created|settled)$");
        assertThat(alternation).contains("(2):").contains("orders.created").contains("payments.settled")
                .doesNotContain("dlq");
    }

    @Test
    void listTopicsReturnsNoTopicsMessageWhenNothingMatches() throws Exception {
        ListTopicsResult result = mock(ListTopicsResult.class);
        when(result.names()).thenReturn(completed(Set.of("payments.settled")));
        when(adminClient.listTopics()).thenReturn(result);

        String output = tools.listTopics("test-cluster", "^orders\\.");

        assertThat(output).contains("No topics found matching regex '^orders\\.'");
    }

    @Test
    void listTopicsRejectsBlankRegexWithoutQueryingTheCluster() {
        String output = tools.listTopics("test-cluster", "");

        assertThat(output).contains("non-empty regex is required");
        verifyNoInteractions(adminClient);
    }

    @Test
    void listTopicsReportsInvalidRegexWithoutQueryingTheCluster() {
        String output = tools.listTopics("test-cluster", "orders.(");

        assertThat(output).startsWith("Invalid regex 'orders.('");
        verifyNoInteractions(adminClient);
    }

    @Test
    void listTopicsParksLongResultAsSnippetWithKeyHoldingFullList() throws Exception {
        Set<String> names = IntStream.range(0, 8).mapToObj(i -> "orders." + i).collect(Collectors.toSet());
        ListTopicsResult result = mock(ListTopicsResult.class);
        when(result.names()).thenReturn(completed(names));
        when(adminClient.listTopics()).thenReturn(result);

        String output = tools.listTopics("test-cluster", "^orders\\.");

        assertThat(output).contains("(8):").contains("3 more not shown");
        assertThat(output.lines().filter(line -> line.startsWith("  - "))).hasSize(5);
        assertThat(store.find(collectionKey(output))).hasValueSatisfying(
                stored -> assertThat(stored).containsExactlyElementsOf(names.stream().sorted().toList()));
    }

    @Test
    void listTopicsReturnsShortResultCompleteWithoutKey() throws Exception {
        ListTopicsResult result = mock(ListTopicsResult.class);
        when(result.names()).thenReturn(completed(Set.of("orders.a", "orders.b")));
        when(adminClient.listTopics()).thenReturn(result);

        String output = tools.listTopics("test-cluster", "^orders\\.");

        assertThat(output).contains("orders.a").contains("orders.b").doesNotContain("ollection key");
    }

    @Test
    void describeTopicListsEveryPartitionAndMarksUnderReplicatedOnes() throws Exception {
        Node leader = new Node(0, "host0", 9092);
        Node follower = new Node(1, "host1", 9092);
        List<TopicPartitionInfo> partitions = IntStream.range(0, 7)
                .mapToObj(i -> new TopicPartitionInfo(i, leader, List.of(leader, follower),
                        i == 3 ? List.of(leader) : List.of(leader, follower)))
                .toList();
        stubDescribeTopic("orders", new TopicDescription("orders", false, partitions));

        String output = tools.describeTopic("test-cluster", "orders");

        assertThat(output).contains("Topic: orders  partitions: 7  internal: false  under-replicated: 1");
        assertThat(output).contains("Partitions (7):").contains("2 more not shown");
        List<String> stored = store.find(collectionKey(output)).orElseThrow();
        assertThat(stored).hasSize(7);
        assertThat(stored.get(3)).isEqualTo("partition 3  leader: 0  replicas: [0,1]  isr: [0]  [UNDER-REPLICATED]");
        assertThat(stored.get(0)).doesNotContain("UNDER-REPLICATED");
    }

    @Test
    void describeTopicShowsAllPartitionsWithoutKeyWhenFiveOrFewer() throws Exception {
        Node leader = new Node(0, "host0", 9092);
        List<TopicPartitionInfo> partitions = IntStream.range(0, 2)
                .mapToObj(i -> new TopicPartitionInfo(i, leader, List.of(leader), List.of(leader)))
                .toList();
        stubDescribeTopic("orders", new TopicDescription("orders", false, partitions));

        String output = tools.describeTopic("test-cluster", "orders");

        assertThat(output).contains("under-replicated: 0").contains("Partitions (2):")
                .contains("partition 0  leader: 0").contains("partition 1  leader: 0")
                .doesNotContain("ollection key");
    }

    @Test
    void describeBrokerConfigsParksLongResultWithFullSortedRows() throws Exception {
        List<ConfigEntry> entries = IntStream.range(0, 7)
                .mapToObj(i -> new ConfigEntry("key." + i, "v" + i, ConfigEntry.ConfigSource.DYNAMIC_BROKER_CONFIG,
                        false, false, List.of(), ConfigEntry.ConfigType.STRING, null))
                .toList();
        ConfigResource resource = new ConfigResource(ConfigResource.Type.BROKER, "0");
        DescribeConfigsResult result = mock(DescribeConfigsResult.class);
        when(result.all()).thenReturn(completed(Map.of(resource, new Config(entries))));
        when(adminClient.describeConfigs(List.of(resource))).thenReturn(result);

        String output = tools.describeBrokerConfigs("test-cluster", 0);

        assertThat(output).contains("Broker 0 configs (7):").contains("key.0 = v0").contains("2 more not shown");
        assertThat(store.find(collectionKey(output))).hasValueSatisfying(stored ->
                assertThat(stored).hasSize(7).first().isEqualTo("key.0 = v0"));
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
    void listConsumerGroupsRejectsBlankRegexWithoutQueryingTheCluster() {
        String output = tools.listConsumerGroups("nonexistent-cluster", "");

        assertThat(output).contains("non-empty regex is required").doesNotContain("Unknown cluster");
    }

    @Test
    void listConsumerGroupsReportsInvalidRegexWithoutQueryingTheCluster() {
        String output = tools.listConsumerGroups("nonexistent-cluster", "[");

        assertThat(output).startsWith("Invalid regex '['").doesNotContain("Unknown cluster");
    }
}
