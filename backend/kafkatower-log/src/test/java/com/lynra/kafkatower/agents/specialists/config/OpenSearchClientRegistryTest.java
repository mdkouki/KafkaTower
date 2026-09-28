package com.lynra.kafkatower.agents.specialists.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenSearchClientRegistryTest {

    private OsProperties.ClusterOs cluster(String url) {
        OsProperties.ClusterOs clusterOs = new OsProperties.ClusterOs();
        clusterOs.setUrl(url);
        return clusterOs;
    }

    @Test
    void buildsOneProviderPerConfiguredCluster() {
        OsProperties properties = new OsProperties();
        properties.setClusters(Map.of(
                "nonprod", cluster("http://localhost:9200"),
                "prod", cluster("http://localhost:9201")));

        OpenSearchClientRegistry registry = new OpenSearchClientRegistry(properties);

        assertThat(registry.clusterNames()).containsExactly("nonprod", "prod");
        assertThat(registry.isEmpty()).isFalse();
    }

    @Test
    void forClusterReturnsEmptyForAnUnconfiguredCluster() {
        OsProperties properties = new OsProperties();
        properties.setClusters(Map.of("nonprod", cluster("http://localhost:9200")));

        OpenSearchClientRegistry registry = new OpenSearchClientRegistry(properties);

        assertThat(registry.forCluster("nonprod")).isPresent();
        assertThat(registry.forCluster("prod")).isEmpty();
    }

    @Test
    void defaultClusterIsPresentOnlyWhenItMatchesAConfiguredCluster() {
        OsProperties properties = new OsProperties();
        properties.setClusters(Map.of("nonprod", cluster("http://localhost:9200")));
        properties.setDefaultCluster("nonprod");

        OpenSearchClientRegistry registry = new OpenSearchClientRegistry(properties);

        assertThat(registry.defaultCluster()).contains("nonprod");
    }

    @Test
    void defaultClusterIsEmptyWhenItDoesNotMatchAnyConfiguredCluster() {
        OsProperties properties = new OsProperties();
        properties.setClusters(Map.of("nonprod", cluster("http://localhost:9200")));
        properties.setDefaultCluster("staging");

        OpenSearchClientRegistry registry = new OpenSearchClientRegistry(properties);

        assertThat(registry.defaultCluster()).isEmpty();
    }

    @Test
    void isEmptyWhenNoClustersAreConfigured() {
        OpenSearchClientRegistry registry = new OpenSearchClientRegistry(new OsProperties());

        assertThat(registry.isEmpty()).isTrue();
        assertThat(registry.clusterNames()).isEmpty();
    }
}
