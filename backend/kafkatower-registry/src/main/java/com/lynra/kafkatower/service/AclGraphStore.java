package com.lynra.kafkatower.service;

import com.lynra.kafkatower.model.ClusterAclGraph;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Holds the latest {@link ClusterAclGraph} per cluster in memory, replaced wholesale on each refresh. */
@Service
public class AclGraphStore {

    private final Map<String, ClusterAclGraph> byCluster = new ConcurrentHashMap<>();

    public void put(ClusterAclGraph graph) {
        byCluster.put(graph.clusterName(), graph);
    }

    public Optional<ClusterAclGraph> get(String clusterName) {
        return Optional.ofNullable(byCluster.get(clusterName));
    }

    public List<String> clusterNames() {
        return List.copyOf(byCluster.keySet());
    }
}
