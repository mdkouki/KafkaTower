package com.lynra.kafkatower.service;

import com.lynra.kafkatower.model.ClusterAclGraph;
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
class AclGraphRefreshJobTest {

    @Mock
    private AclGraphBuilder builder;

    private AclGraphStore store;
    private Map<String, AdminClient> kafkaAdminMap;
    private AclGraphRefreshJob job;

    @BeforeEach
    void setUp() {
        store = new AclGraphStore();
        kafkaAdminMap = new HashMap<>();
        kafkaAdminMap.put("nonprod", mock(AdminClient.class));
        kafkaAdminMap.put("prod", mock(AdminClient.class));
        job = new AclGraphRefreshJob(kafkaAdminMap, builder, store);
    }

    @Test
    void rebuildsEveryConfiguredClusterAndReplacesStoreEntries() {
        when(builder.build("nonprod")).thenReturn(new ClusterAclGraph("nonprod", Map.of(), Instant.now(), null));
        when(builder.build("prod")).thenReturn(new ClusterAclGraph("prod", Map.of(), Instant.now(), null));

        job.refresh();

        verify(builder).build("nonprod");
        verify(builder).build("prod");
        assertEquals(2, store.clusterNames().size());
    }

    @Test
    void refreshReplacesAPreviousGraphForTheSameCluster() {
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        Instant second = Instant.parse("2026-01-02T00:00:00Z");
        when(builder.build("nonprod")).thenReturn(new ClusterAclGraph("nonprod", Map.of(), first, null));
        when(builder.build("prod")).thenReturn(new ClusterAclGraph("prod", Map.of(), first, null));
        job.refresh();

        when(builder.build("nonprod")).thenReturn(new ClusterAclGraph("nonprod", Map.of(), second, null));
        when(builder.build("prod")).thenReturn(new ClusterAclGraph("prod", Map.of(), second, null));
        job.refresh();

        assertEquals(second, store.get("nonprod").orElseThrow().builtAt());
    }

    @Test
    void skipsRefreshWhenNoClustersConfigured() {
        AclGraphRefreshJob emptyJob = new AclGraphRefreshJob(new HashMap<>(), builder, store);

        emptyJob.refresh();

        assertEquals(0, store.clusterNames().size());
    }
}
