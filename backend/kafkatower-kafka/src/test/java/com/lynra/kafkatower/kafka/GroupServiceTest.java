package com.lynra.kafkatower.kafka;

import com.lynra.kafkatower.testsupport.AdminClientTestSupport;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroupServiceTest {

    @Mock
    private AdminClient adminClient;

    private GroupService groupService;
    private Map<String, AdminClient> kafkaAdminMap;

    @BeforeEach
    void setUp() {
        kafkaAdminMap = new HashMap<>();
        kafkaAdminMap.put("test-cluster", adminClient);
        groupService = new GroupService(kafkaAdminMap);
    }

    @Test
    void testGetGroupStateClusterNotFound() {
        GroupInfo result = groupService.getGroupState("test-group", "nonexistent-cluster");

        assertNull(result);
    }

    @Test
    void testGetGroupStateNullCluster() {
        GroupInfo result = groupService.getGroupState("test-group", null);

        assertNull(result);
    }

    @Test
    void testGetGroupStateWithException() throws Exception {
        when(adminClient.describeConsumerGroups(any()))
                .thenThrow(new RuntimeException("Kafka error"));

        assertThrows(GroupLookupException.class,
                () -> groupService.getGroupState("test-group", "test-cluster"));
    }

    @Test
    void testGetSubscribedTopics() throws Exception {
        Set<String> topics = groupService.getSubscribedTopics("test-group", "test-cluster");

        assertNotNull(topics);
        assertTrue(topics.isEmpty());
    }

    @Test
    void testGetSubscribedTopicsClusterNotFound() {
        Set<String> topics = groupService.getSubscribedTopics("test-group", "nonexistent-cluster");

        assertTrue(topics.isEmpty());
    }

    @Test
    void testGetSubscribedTopicsWithException() throws Exception {
        when(adminClient.describeConsumerGroups(any()))
                .thenThrow(new RuntimeException("Connection failed"));

        Set<String> topics = groupService.getSubscribedTopics("test-group", "test-cluster");

        assertTrue(topics.isEmpty());
    }

    @Test
    void testGetSubscribedTopicsNullGroup() {
        Set<String> topics = groupService.getSubscribedTopics(null, "test-cluster");

        assertNotNull(topics);
    }

    @Test
    void testGroupServiceWithMultipleClusters() {
        Map<String, AdminClient> multipleClusters = new HashMap<>();
        AdminClient cluster1 = mock(AdminClient.class);
        AdminClient cluster2 = mock(AdminClient.class);

        multipleClusters.put("cluster1", cluster1);
        multipleClusters.put("cluster2", cluster2);

        GroupService service = new GroupService(multipleClusters);
        assertNotNull(service);
    }

    @Test
    void testGroupServiceEmptyClusterMap() {
        GroupService service = new GroupService(new HashMap<>());
        GroupInfo result = service.getGroupState("test-group", "cluster");

        assertNull(result);
    }

    @Test
    void testGetGroupStateWithNullGroupName() {
        // AdminClient.describeConsumerGroups(List.of(null)) itself rejects a null element before
        // any network call is made — GroupService surfaces that as a lookup failure, not "not found".
        assertThrows(GroupLookupException.class,
                () -> groupService.getGroupState(null, "test-cluster"));
    }

    @Test
    void testGetGroupStateWithEmptyGroupName() throws Exception {
        AdminClientTestSupport.stubGroupNotFound(adminClient, "");

        GroupInfo result = groupService.getGroupState("", "test-cluster");

        assertNull(result);
    }

    @Test
    void testGroupServiceConstructor() {
        assertNotNull(groupService);
    }

    @Test
    void testGetSubscribedTopicsMultipleCalls() {
        Set<String> result1 = groupService.getSubscribedTopics("group1", "test-cluster");
        Set<String> result2 = groupService.getSubscribedTopics("group2", "test-cluster");

        assertNotNull(result1);
        assertNotNull(result2);
    }

    @Test
    void testGetGroupStateMultipleCalls() throws Exception {
        AdminClientTestSupport.stubGroupNotFound(adminClient, "group1");
        AdminClientTestSupport.stubGroupNotFound(adminClient, "group2");

        GroupInfo result1 = groupService.getGroupState("group1", "test-cluster");
        GroupInfo result2 = groupService.getGroupState("group2", "test-cluster");

        assertNull(result1);
        assertNull(result2);
    }
}
