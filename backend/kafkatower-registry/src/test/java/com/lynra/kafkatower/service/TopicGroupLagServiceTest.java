package com.lynra.kafkatower.service;

import com.lynra.kafkatower.kafka.GroupInfo;
import com.lynra.kafkatower.kafka.GroupInfo.PartitionLag;
import com.lynra.kafkatower.kafka.GroupLookupException;
import com.lynra.kafkatower.kafka.GroupService;
import com.lynra.kafkatower.model.TopicGroupLag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TopicGroupLagServiceTest {

    private static final String CLUSTER = "local";
    private static final String TOPIC = "orders";

    @Mock
    private GroupService groupService;

    private TopicGroupLagService service;

    @BeforeEach
    void setUp() {
        service = new TopicGroupLagService(groupService);
    }

    @Test
    void sumsLagOnlyForRequestedTopicAndSortsByGroupId() {
        givenGroups("zeta", "alpha");
        givenGroup(group("zeta", "Stable", lag(TOPIC, 4), lag("other", 100)));
        givenGroup(group("alpha", "Empty", lag(TOPIC, 1), lag(TOPIC, 2)));

        List<TopicGroupLag> result = service.groupsForTopic(CLUSTER, TOPIC);

        assertThat(result).containsExactly(
                new TopicGroupLag("alpha", "Empty", 3),
                new TopicGroupLag("zeta", "Stable", 4));
    }

    @Test
    void ignoresGroupsNotConsumingTheTopic() {
        givenGroups("g1");
        givenGroup(group("g1", "Stable", lag("other", 5)));

        assertThat(service.groupsForTopic(CLUSTER, TOPIC)).isEmpty();
    }

    @Test
    void treatsNegativeLagAsZero() {
        givenGroups("g1");
        givenGroup(group("g1", "Stable", lag(TOPIC, -10), lag(TOPIC, 7)));

        assertThat(service.groupsForTopic(CLUSTER, TOPIC)).containsExactly(new TopicGroupLag("g1", "Stable", 7));
    }

    @Test
    void skipsGroupsWithoutStateOrThatFailLookup() {
        givenGroups("missing", "broken", "ok");
        when(groupService.getGroupState("missing", CLUSTER)).thenReturn(null);
        when(groupService.getGroupState("broken", CLUSTER)).thenThrow(new GroupLookupException("timeout", null));
        givenGroup(group("ok", "Stable", lag(TOPIC, 2)));

        assertThat(service.groupsForTopic(CLUSTER, TOPIC)).containsExactly(new TopicGroupLag("ok", "Stable", 2));
    }

    private void givenGroups(String... groupIds) {
        when(groupService.getAllGroupIds(CLUSTER)).thenReturn(List.of(groupIds));
    }

    private void givenGroup(GroupInfo group) {
        when(groupService.getGroupState(group.groupId(), CLUSTER)).thenReturn(group);
    }

    private static GroupInfo group(String id, String state, PartitionLag... lags) {
        return new GroupInfo(id, state, "coordinator", "range", List.of(), List.of(lags), List.of(), 0);
    }

    private static PartitionLag lag(String topic, long lag) {
        return new PartitionLag(topic, 0, 0, lag, lag);
    }
}
