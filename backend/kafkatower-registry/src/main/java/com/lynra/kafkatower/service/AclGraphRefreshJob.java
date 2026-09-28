package com.lynra.kafkatower.service;

import jakarta.annotation.PostConstruct;
import org.apache.kafka.clients.admin.AdminClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Periodically rebuilds the in-memory ACL graph for every configured Kafka cluster
 * ({@code kafka.clusters.*}), one {@link AclGraphBuilder#build} call per cluster. REST reads
 * always come from {@link AclGraphStore}, never trigger a live Kafka call themselves.
 */
@Component
public class AclGraphRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(AclGraphRefreshJob.class);

    private final Map<String, AdminClient> kafkaAdminMap;
    private final AclGraphBuilder builder;
    private final AclGraphStore store;

    public AclGraphRefreshJob(Map<String, AdminClient> kafkaAdminMap, AclGraphBuilder builder, AclGraphStore store) {
        this.kafkaAdminMap = kafkaAdminMap;
        this.builder = builder;
        this.store = store;
    }

    @PostConstruct
    void initialBuild() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${kafkatower.registry.acl-graph.refresh-interval-ms:300000}")
    void refresh() {
        Set<String> clusters = kafkaAdminMap.keySet();
        if (clusters.isEmpty()) {
            log.debug("ACL graph refresh: no Kafka clusters configured, skipping.");
            return;
        }
        long start = System.currentTimeMillis();
        for (String clusterName : clusters) {
            store.put(builder.build(clusterName));
        }
        log.info("ACL graph refresh: rebuilt {} cluster(s) in {}ms", clusters.size(), System.currentTimeMillis() - start);
    }
}
