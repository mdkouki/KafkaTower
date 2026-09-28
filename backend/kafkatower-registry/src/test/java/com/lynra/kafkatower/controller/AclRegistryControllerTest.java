package com.lynra.kafkatower.controller;

import com.lynra.kafkatower.kafka.GroupService;
import com.lynra.kafkatower.model.AclClusterStatus;
import com.lynra.kafkatower.model.AclGroupGrant;
import com.lynra.kafkatower.model.AclUserSummaryLite;
import com.lynra.kafkatower.model.ClusterAclGraph;
import com.lynra.kafkatower.model.UserAclSummary;
import com.lynra.kafkatower.service.AclGraphStore;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AclRegistryControllerTest {

    @Mock
    private AclGraphStore store;

    @Mock
    private GroupService groupService;

    private AclRegistryController controller;

    private UserAclSummary alice;
    private ClusterAclGraph graph;

    @BeforeEach
    void setUp() {
        Map<String, AdminClient> kafkaAdminMap = new HashMap<>();
        kafkaAdminMap.put("prod", mock(AdminClient.class));
        kafkaAdminMap.put("nonprod", mock(AdminClient.class));
        controller = new AclRegistryController(store, kafkaAdminMap, groupService);

        alice = new UserAclSummary("User:alice",
                List.of("orders.events"), List.of("orders.audit"),
                List.of(new AclGroupGrant("orders-consumer", false)), List.of("orders-txn"));
        graph = new ClusterAclGraph("prod", Map.of("User:alice", alice), Instant.parse("2026-01-01T00:00:00Z"), null);
    }

    @Test
    void listClustersReturnsConfiguredLiveClustersSorted() {
        List<String> clusters = controller.listClusters();

        assertEquals(List.of("nonprod", "prod"), clusters);
    }

    @Test
    void listUsersReturnsSortedLiteRows() {
        when(store.get("prod")).thenReturn(Optional.of(graph));

        ResponseEntity<List<AclUserSummaryLite>> response = controller.listUsers("prod");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        assertEquals("User:alice", response.getBody().get(0).principal());
        assertEquals(1, response.getBody().get(0).consumeTopicCount());
    }

    @Test
    void listUsersReturns404ForUnknownCluster() {
        when(store.get("missing")).thenReturn(Optional.empty());

        ResponseEntity<List<AclUserSummaryLite>> response = controller.listUsers("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void getUserReturnsFullDetail() {
        when(store.get("prod")).thenReturn(Optional.of(graph));

        ResponseEntity<UserAclSummary> response = controller.getUser("prod", "User:alice");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(alice, response.getBody());
    }

    @Test
    void getUserReturns404WhenPrincipalUnknown() {
        when(store.get("prod")).thenReturn(Optional.of(graph));

        ResponseEntity<UserAclSummary> response = controller.getUser("prod", "User:ghost");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void statusReturnsGraphFreshness() {
        when(store.get("prod")).thenReturn(Optional.of(graph));

        ResponseEntity<AclClusterStatus> response = controller.status("prod");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().userCount());
        assertEquals(graph.builtAt(), response.getBody().builtAt());
    }

    @Test
    void groupsForGrantFiltersToPrefixMatchesWhenPrefixed() {
        when(groupService.listGroupsWithState("prod")).thenReturn(List.of(
                new GroupService.GroupSummary("orders-consumer-1", "Stable"),
                new GroupService.GroupSummary("orders-consumer-2", "Empty"),
                new GroupService.GroupSummary("billing-consumer", "Stable")));

        List<GroupService.GroupSummary> result = controller.groupsForGrant("prod", "orders-", true);

        assertEquals(2, result.size());
        assertEquals("orders-consumer-1", result.get(0).groupId());
        assertEquals("orders-consumer-2", result.get(1).groupId());
    }

    @Test
    void groupsForGrantMatchesExactlyWhenLiteral() {
        when(groupService.listGroupsWithState("prod")).thenReturn(List.of(
                new GroupService.GroupSummary("orders-consumer", "Stable"),
                new GroupService.GroupSummary("orders-consumer-2", "Empty")));

        List<GroupService.GroupSummary> result = controller.groupsForGrant("prod", "orders-consumer", false);

        assertEquals(1, result.size());
        assertEquals("orders-consumer", result.get(0).groupId());
    }
}
