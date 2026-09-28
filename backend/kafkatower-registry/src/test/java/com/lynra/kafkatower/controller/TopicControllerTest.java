package com.lynra.kafkatower.controller;

import com.lynra.kafkatower.kafka.GroupInfo;
import com.lynra.kafkatower.kafka.GroupService;
import com.lynra.kafkatower.model.ClusterTopicGraph;
import com.lynra.kafkatower.model.TopicClusterStatus;
import com.lynra.kafkatower.model.TopicDetail;
import com.lynra.kafkatower.model.TopicGroupLag;
import com.lynra.kafkatower.model.TopicSummaryLite;
import com.lynra.kafkatower.service.TopicGraphStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TopicControllerTest {

    @Mock
    private TopicGraphStore store;

    @Mock
    private GroupService groupService;

    private TopicController controller;

    private TopicDetail ordersEvents;
    private ClusterTopicGraph graph;

    @BeforeEach
    void setUp() {
        controller = new TopicController(store, groupService);

        ordersEvents = new TopicDetail("orders.events", 3, 2, false,
                Map.of("retention.ms", "86400000"), List.of("User:bob"), List.of("User:alice"));
        graph = new ClusterTopicGraph("prod", Map.of("orders.events", ordersEvents), Instant.parse("2026-01-01T00:00:00Z"), null);
    }

    @Test
    void listTopicsReturnsSortedLiteRows() {
        when(store.get("prod")).thenReturn(Optional.of(graph));

        ResponseEntity<List<TopicSummaryLite>> response = controller.listTopics("prod");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        assertEquals("orders.events", response.getBody().get(0).name());
        assertEquals(3, response.getBody().get(0).partitionCount());
    }

    @Test
    void listTopicsReturns404ForUnknownCluster() {
        when(store.get("missing")).thenReturn(Optional.empty());

        ResponseEntity<List<TopicSummaryLite>> response = controller.listTopics("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void getTopicReturnsFullDetail() {
        when(store.get("prod")).thenReturn(Optional.of(graph));

        ResponseEntity<TopicDetail> response = controller.getTopic("prod", "orders.events");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(ordersEvents, response.getBody());
    }

    @Test
    void getTopicReturns404WhenTopicUnknown() {
        when(store.get("prod")).thenReturn(Optional.of(graph));

        ResponseEntity<TopicDetail> response = controller.getTopic("prod", "ghost.topic");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void statusReturnsGraphFreshness() {
        when(store.get("prod")).thenReturn(Optional.of(graph));

        ResponseEntity<TopicClusterStatus> response = controller.status("prod");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().topicCount());
        assertEquals(graph.builtAt(), response.getBody().builtAt());
    }

    @Test
    void groupsForTopicFiltersToGroupsAssignedToThatTopic() {
        when(groupService.getAllGroupIds("prod")).thenReturn(List.of("orders-consumer", "billing-consumer"));

        GroupInfo ordersGroup = new GroupInfo("orders-consumer", "Stable", "broker 1", "range",
                List.of(),
                List.of(new GroupInfo.PartitionLag("orders.events", 0, 10, 15, 5)),
                List.of(), 5);
        GroupInfo billingGroup = new GroupInfo("billing-consumer", "Stable", "broker 1", "range",
                List.of(),
                List.of(new GroupInfo.PartitionLag("billing.events", 0, 10, 10, 0)),
                List.of(), 0);

        when(groupService.getGroupState("orders-consumer", "prod")).thenReturn(ordersGroup);
        when(groupService.getGroupState("billing-consumer", "prod")).thenReturn(billingGroup);

        List<TopicGroupLag> result = controller.groupsForTopic("prod", "orders.events");

        assertEquals(1, result.size());
        assertEquals("orders-consumer", result.get(0).groupId());
        assertEquals(5, result.get(0).lag());
    }
}
