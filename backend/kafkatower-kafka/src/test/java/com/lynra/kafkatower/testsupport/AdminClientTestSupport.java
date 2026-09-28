package com.lynra.kafkatower.testsupport;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeConsumerGroupsResult;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.internals.KafkaFutureImpl;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Shared Mockito/KafkaFuture plumbing for AdminClient-backed tests. */
public final class AdminClientTestSupport {

    private AdminClientTestSupport() {
    }

    public static <T> KafkaFuture<T> completed(T value) {
        KafkaFutureImpl<T> future = new KafkaFutureImpl<>();
        future.complete(value);
        return future;
    }

    /** Stubs {@code group} as having no description and no committed offsets on {@code adminClient}. */
    public static void stubGroupNotFound(AdminClient adminClient, String group) throws Exception {
        DescribeConsumerGroupsResult describeResult = mock(DescribeConsumerGroupsResult.class);
        when(describeResult.all()).thenReturn(completed(Map.of()));
        when(adminClient.describeConsumerGroups(List.of(group))).thenReturn(describeResult);

        ListConsumerGroupOffsetsResult offsetsResult = mock(ListConsumerGroupOffsetsResult.class);
        when(offsetsResult.partitionsToOffsetAndMetadata()).thenReturn(completed(Map.of()));
        when(adminClient.listConsumerGroupOffsets(group)).thenReturn(offsetsResult);
    }
}
