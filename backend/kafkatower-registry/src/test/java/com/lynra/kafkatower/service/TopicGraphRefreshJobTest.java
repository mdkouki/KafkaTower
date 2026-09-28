package com.lynra.kafkatower.service;

import com.lynra.kafkatower.model.ClusterTopicGraph;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TopicGraphRefreshJobTest {

    @Mock
    private TopicGraphBuilder builder;

    private TopicGraphStore store;
    private Map<String, AdminClient> kafkaAdminMap;
    private TopicGraphRefreshJob job;

    @BeforeEach
    void setUp() {
        store = new TopicGraphStore();
        kafkaAdminMap = new HashMap<>();
        kafkaAdminMap.put("nonprod", mock(AdminClient.class));
        kafkaAdminMap.put("prod", mock(AdminClient.class));
        job = new TopicGraphRefreshJob(kafkaAdminMap, builder, store);
    }

    @Test
    void rebuildsEveryConfiguredClusterAndReplacesStoreEntries() {
        when(builder.build("nonprod")).thenReturn(new ClusterTopicGraph("nonprod", Map.of(), Instant.now(), null));
        when(builder.build("prod")).thenReturn(new ClusterTopicGraph("prod", Map.of(), Instant.now(), null));

        job.refresh();

        verify(builder).build("nonprod");
        verify(builder).build("prod");
        assertEquals(2, store.clusterNames().size());
    }

    @Test
    void skipsRefreshWhenNoClustersConfigured() {
        TopicGraphRefreshJob emptyJob = new TopicGraphRefreshJob(new HashMap<>(), builder, store);

        emptyJob.refresh();

        assertEquals(0, store.clusterNames().size());
    }
}
