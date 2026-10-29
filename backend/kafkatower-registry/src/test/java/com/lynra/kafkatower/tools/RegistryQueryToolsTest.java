package com.lynra.kafkatower.tools;

import com.lynra.kafkatower.kafka.GroupInfo;
import com.lynra.kafkatower.kafka.GroupService;
import com.lynra.kafkatower.model.AclGroupGrant;
import com.lynra.kafkatower.model.ClusterAclGraph;
import com.lynra.kafkatower.model.ClusterTopicGraph;
import com.lynra.kafkatower.model.TopicDetail;
import com.lynra.kafkatower.model.UserAclSummary;
import com.lynra.kafkatower.service.AclGraphStore;
import com.lynra.kafkatower.service.TopicGraphStore;
import com.lynra.kafkatower.service.TopicGroupLagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegistryQueryToolsTest {

    private final TopicGraphStore topicStore = new TopicGraphStore();
    private final AclGraphStore aclStore = new AclGraphStore();
    private final GroupService groupService = mock(GroupService.class);
    private final RegistryQueryTools tools = new RegistryQueryTools(topicStore, aclStore, new TopicGroupLagService(groupService));

    @BeforeEach
    void seed() {
        topicStore.put(new ClusterTopicGraph("prod", Map.of(
                "orders.events", new TopicDetail("orders.events", 6, 3, false, Map.of(),
                        List.of("User:order-service"), List.of("User:logistics-service", "User:billing")),
                "orders.dlq", new TopicDetail("orders.dlq", 1, 3, false, Map.of(), List.of(), List.of())),
                Instant.now(), null));
        aclStore.put(new ClusterAclGraph("prod", Map.of(
                "User:order-service", new UserAclSummary("User:order-service", List.of(),
                        List.of("orders.events"), List.of(), List.of("order-txn*")),
                "User:logistics-service", new UserAclSummary("User:logistics-service", List.of("orders.events"),
                        List.of("shipments.*"), List.of(new AclGroupGrant("logistics", true)), List.of()),
                "User:billing", new UserAclSummary("User:billing", List.of("orders.events"), List.of(), List.of(), List.of())),
                Instant.now(), null));
    }

    @Test
    void findTopicsRequiresAPrefix() {
        assertThat(tools.findTopics("prod", " ")).contains("prefix is required");
    }

    @Test
    void findTopicsListsMatchesWithCounts() {
        assertThat(tools.findTopics("prod", "orders.e")).contains("orders.events").contains("producers=1 consumers=2")
                .doesNotContain("orders.dlq");
    }

    @Test
    void whoUsesTopicReportsProducersAndConsumers() {
        assertThat(tools.whoUsesTopic("prod", "orders.events"))
                .contains("producers (1): User:order-service")
                .contains("consumers (2): User:billing, User:logistics-service");
    }

    @Test
    void whoUsesTopicSaysWhenUnknown() {
        assertThat(tools.whoUsesTopic("prod", "nope")).contains("not in the registry").contains("findTopics");
    }

    @Test
    void unbuiltClusterListsWhatTheRegistryHas() {
        assertThat(tools.whoUsesTopic("dev", "x")).contains("not been built for cluster 'dev'").contains("prod");
    }

    @Test
    void failedRefreshIsReportedNotHidden() {
        topicStore.put(ClusterTopicGraph.failed("bad", "timeout"));
        assertThat(tools.whoUsesTopic("bad", "x")).contains("failed its last refresh").contains("timeout");
    }

    @Test
    void findPrincipalsMatchesCaseInsensitively() {
        assertThat(tools.findPrincipals("prod", "SERVICE")).contains("User:order-service").contains("User:logistics-service")
                .doesNotContain("User:billing");
    }

    @Test
    void describePrincipalKeepsPrefixMarkersAndGroupGrants() {
        assertThat(tools.describePrincipal("prod", "User:logistics-service"))
                .contains("produces to (1): shipments.*").contains("consumer groups: logistics*");
    }

    @Test
    void principalDependenciesShowsDownstreamConsumers() {
        assertThat(tools.principalDependencies("prod", "User:order-service"))
                .contains("downstream (consume topics it produces) (2)")
                .contains("User:billing via orders.events").contains("User:logistics-service via orders.events");
    }

    @Test
    void unknownPrincipalPointsToFindPrincipals() {
        assertThat(tools.describePrincipal("prod", "User:ghost")).contains("findPrincipals");
    }

    @Test
    void topicConsumerGroupsFiltersToTheTopicAndSumsLag() throws Exception {
        when(groupService.getAllGroupIds("prod")).thenReturn(List.of("g1", "g2"));
        when(groupService.getGroupState("g1", "prod")).thenReturn(group("g1", "orders.events", 40));
        when(groupService.getGroupState("g2", "prod")).thenReturn(group("g2", "other", 5));

        assertThat(tools.topicConsumerGroups("prod", "orders.events")).contains("g1  state=Stable  lag=40")
                .doesNotContain("g2");
    }

    private static GroupInfo group(String id, String topic, long lag) {
        return new GroupInfo(id, "Stable", "b1", "range", List.of(),
                List.of(new GroupInfo.PartitionLag(topic, 0, 0, lag, lag)), List.of(), lag);
    }
}
