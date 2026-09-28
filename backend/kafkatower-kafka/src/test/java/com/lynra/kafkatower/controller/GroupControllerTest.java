package com.lynra.kafkatower.controller;

import com.lynra.kafkatower.kafka.GroupInfo;
import com.lynra.kafkatower.kafka.GroupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroupControllerTest {

    @Mock
    private GroupService groupService;

    @InjectMocks
    private GroupController groupController;

    private GroupTestData testData;

    record GroupInfoFixture(
            String groupId,
            String state,
            List<GroupInfo.MemberInfo> members,
            List<GroupInfo.PartitionLag> lags,
            Long totalLag
    ) {
        static GroupInfoFixture stable() {
            List<GroupInfo.MemberInfo> members = List.of(
                    new GroupInfo.MemberInfo("consumer-1", "client-1", "localhost:9092", List.of("topic-0", "topic-1"))
            );
            List<GroupInfo.PartitionLag> lags = List.of(
                    new GroupInfo.PartitionLag("topic", 0, 100, 150, 50)
            );
            return new GroupInfoFixture("test-group", "STABLE", members, lags, 50L);
        }

        static GroupInfoFixture empty() {
            return new GroupInfoFixture("empty-group", "EMPTY", List.of(), List.of(), 0L);
        }

        static GroupInfoFixture rebalancing() {
            return new GroupInfoFixture("group", "REBALANCING", List.of(), List.of(), 0L);
        }

        static GroupInfoFixture multiMember() {
            List<GroupInfo.MemberInfo> members = List.of(
                    new GroupInfo.MemberInfo("consumer-1", "client-1", "localhost:9092", List.of("topic-0")),
                    new GroupInfo.MemberInfo("consumer-2", "client-2", "localhost:9093", List.of("topic-1")),
                    new GroupInfo.MemberInfo("consumer-3", "client-3", "localhost:9094", List.of("topic-2"))
            );
            return new GroupInfoFixture("test-group", "STABLE", members, List.of(), 0L);
        }

        static GroupInfoFixture withLag(long lag) {
            List<GroupInfo.PartitionLag> lags = List.of(
                    new GroupInfo.PartitionLag("topic1", 0, 100, 200, 100),
                    new GroupInfo.PartitionLag("topic1", 1, 50, 150, 100),
                    new GroupInfo.PartitionLag("topic2", 0, 0, 100, 100)
            );
            return new GroupInfoFixture("test-group", "STABLE", List.of(), lags, lag);
        }

        static GroupInfoFixture zeroLag() {
            List<GroupInfo.PartitionLag> lags = List.of(
                    new GroupInfo.PartitionLag("topic", 0, 100, 100, 0)
            );
            return new GroupInfoFixture("test-group", "STABLE", List.of(), lags, 0L);
        }

        static GroupInfoFixture highLag() {
            List<GroupInfo.PartitionLag> lags = List.of(
                    new GroupInfo.PartitionLag("topic", 0, 0, 1000000, 1000000)
            );
            return new GroupInfoFixture("test-group", "STABLE", List.of(), lags, 1000000L);
        }

        GroupInfo toGroupInfo() {
            return new GroupInfo(groupId, state, null, null, members, lags, List.of(), totalLag);
        }
    }

    static class GroupTestData {
        GroupInfoFixture stable() {
            return GroupInfoFixture.stable();
        }

        GroupInfoFixture empty() {
            return GroupInfoFixture.empty();
        }

        GroupInfoFixture rebalancing() {
            return GroupInfoFixture.rebalancing();
        }

        GroupInfoFixture multiMember() {
            return GroupInfoFixture.multiMember();
        }

        GroupInfoFixture withLag(long lag) {
            return GroupInfoFixture.withLag(lag);
        }

        GroupInfoFixture zeroLag() {
            return GroupInfoFixture.zeroLag();
        }

        GroupInfoFixture highLag() {
            return GroupInfoFixture.highLag();
        }
    }

    @BeforeEach
    void setUp() {
        testData = new GroupTestData();
    }

    @Test
    void testGetGroupStateSuccess() {
        GroupInfoFixture fixture = testData.stable();
        when(groupService.getGroupState("test-group", "test-cluster"))
                .thenReturn(fixture.toGroupInfo());

        ResponseEntity<GroupInfo> response = groupController.getGroupState("test-group", "test-cluster");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("test-group", response.getBody().groupId());
        assertEquals("STABLE", response.getBody().state());
        verify(groupService, times(1)).getGroupState("test-group", "test-cluster");
    }

    @Test
    void testGetGroupStateNotFound() {
        when(groupService.getGroupState("nonexistent", "test-cluster"))
                .thenReturn(null);

        ResponseEntity<GroupInfo> response = groupController.getGroupState("nonexistent", "test-cluster");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void testGetGroupStateClusterNotFound() {
        when(groupService.getGroupState("test-group", "nonexistent-cluster"))
                .thenReturn(null);

        ResponseEntity<GroupInfo> response = groupController.getGroupState("test-group", "nonexistent-cluster");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void testGetGroupStateWithMultipleMembers() {
        GroupInfoFixture fixture = testData.multiMember();
        when(groupService.getGroupState("test-group", "test-cluster"))
                .thenReturn(fixture.toGroupInfo());

        ResponseEntity<GroupInfo> response = groupController.getGroupState("test-group", "test-cluster");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(3, response.getBody().members().size());
    }

    @Test
    void testGetGroupStateWithLag() {
        GroupInfoFixture fixture = testData.withLag(300L);
        when(groupService.getGroupState("test-group", "test-cluster"))
                .thenReturn(fixture.toGroupInfo());

        ResponseEntity<GroupInfo> response = groupController.getGroupState("test-group", "test-cluster");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(300L, response.getBody().totalLag());
    }

    @Test
    void testGetGroupStateEmpty() {
        GroupInfoFixture fixture = testData.empty();
        when(groupService.getGroupState("empty-group", "test-cluster"))
                .thenReturn(fixture.toGroupInfo());

        ResponseEntity<GroupInfo> response = groupController.getGroupState("empty-group", "test-cluster");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().members().isEmpty());
    }

    @Test
    void testGetGroupStateWithDifferentStates() {
        GroupInfoFixture stableFixture = testData.stable();
        GroupInfoFixture rebalancingFixture = testData.rebalancing();

        when(groupService.getGroupState("stable-group", "test-cluster"))
                .thenReturn(stableFixture.toGroupInfo());
        when(groupService.getGroupState("rebalancing-group", "test-cluster"))
                .thenReturn(rebalancingFixture.toGroupInfo());

        ResponseEntity<GroupInfo> response1 = groupController.getGroupState("stable-group", "test-cluster");
        ResponseEntity<GroupInfo> response2 = groupController.getGroupState("rebalancing-group", "test-cluster");

        assertEquals("STABLE", response1.getBody().state());
        assertEquals("REBALANCING", response2.getBody().state());
    }

    @Test
    void testGetGroupStateNullGroupName() {
        when(groupService.getGroupState(null, "test-cluster"))
                .thenReturn(null);

        ResponseEntity<GroupInfo> response = groupController.getGroupState(null, "test-cluster");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void testGetGroupStateNullClusterName() {
        when(groupService.getGroupState("test-group", null))
                .thenReturn(null);

        ResponseEntity<GroupInfo> response = groupController.getGroupState("test-group", null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void testGetGroupStateWithSpecialCharacters() {
        GroupInfoFixture fixture = new GroupInfoFixture("test-group-@#$%", "STABLE", List.of(), List.of(), 0L);

        when(groupService.getGroupState("test-group-@#$%", "test-cluster"))
                .thenReturn(fixture.toGroupInfo());

        ResponseEntity<GroupInfo> response = groupController.getGroupState("test-group-@#$%", "test-cluster");

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testGetGroupStateWithZeroLag() {
        GroupInfoFixture fixture = testData.zeroLag();
        when(groupService.getGroupState("test-group", "test-cluster"))
                .thenReturn(fixture.toGroupInfo());

        ResponseEntity<GroupInfo> response = groupController.getGroupState("test-group", "test-cluster");

        assertEquals(0L, response.getBody().totalLag());
    }

    @Test
    void testGetGroupStateHighLag() {
        GroupInfoFixture fixture = testData.highLag();
        when(groupService.getGroupState("test-group", "test-cluster"))
                .thenReturn(fixture.toGroupInfo());

        ResponseEntity<GroupInfo> response = groupController.getGroupState("test-group", "test-cluster");

        assertEquals(1000000L, response.getBody().totalLag());
    }

    @Test
    void testGetGroupStateMultipleCalls() {
        GroupInfoFixture fixture = testData.stable();
        when(groupService.getGroupState("test-group", "test-cluster"))
                .thenReturn(fixture.toGroupInfo());

        ResponseEntity<GroupInfo> response1 = groupController.getGroupState("test-group", "test-cluster");
        ResponseEntity<GroupInfo> response2 = groupController.getGroupState("test-group", "test-cluster");

        assertEquals(HttpStatus.OK, response1.getStatusCode());
        assertEquals(HttpStatus.OK, response2.getStatusCode());
        verify(groupService, times(2)).getGroupState("test-group", "test-cluster");
    }
}
