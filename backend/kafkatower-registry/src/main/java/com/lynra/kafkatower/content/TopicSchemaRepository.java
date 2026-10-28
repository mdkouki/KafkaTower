package com.lynra.kafkatower.content;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TopicSchemaRepository extends JpaRepository<TopicSchema, String> {

    /** Removes every schema uploaded for a cluster, e.g. when the cluster is deleted from the settings. */
    @org.springframework.transaction.annotation.Transactional
    long deleteByClusterName(String clusterName);

}
