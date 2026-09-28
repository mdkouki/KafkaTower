package com.lynra.kafkatower.service;

import com.lynra.kafkatower.model.ClusterTopicGraph;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Holds the latest {@link ClusterTopicGraph} per cluster in memory, replaced wholesale on each refresh. */
@Service
public class TopicGraphStore {

    private final Map<String, ClusterTopicGraph> byCluster = new ConcurrentHashMap<>();

    public void put(ClusterTopicGraph graph) {
        byCluster.put(graph.clusterName(), graph);
    }

    public Optional<ClusterTopicGraph> get(String clusterName) {
        return Optional.ofNullable(byCluster.get(clusterName));
    }

    public List<String> clusterNames() {
        return List.copyOf(byCluster.keySet());
    }
}
