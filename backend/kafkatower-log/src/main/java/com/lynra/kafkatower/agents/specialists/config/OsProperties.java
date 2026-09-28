package com.lynra.kafkatower.agents.specialists.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Binds {@code agent.mcp.os.*} — one direct OpenSearch REST connection per Kafka cluster.
 * Replaces the earlier MCP-based integration: the OpenSearch version in use here does not
 * support the native MCP server, so {@link com.lynra.kafkatower.tools.OpenSearchQueryTools}
 * talks to the REST API directly, authenticating with basic auth.
 */
@ConfigurationProperties(prefix = "agent.mcp.os")
public class OsProperties {

    private boolean enabled = false;
    private String defaultCluster;
    private Map<String, ClusterOs> clusters = new HashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultCluster() {
        return defaultCluster;
    }

    public void setDefaultCluster(String defaultCluster) {
        this.defaultCluster = defaultCluster;
    }

    public Map<String, ClusterOs> getClusters() {
        return clusters;
    }

    public void setClusters(Map<String, ClusterOs> clusters) {
        this.clusters = clusters;
    }

    public static class ClusterOs {
        private String url;
        private String username;
        private String password;
        /** Default index (or index pattern, e.g. {@code app-logs-*}) queried for this cluster. */
        private String index;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getIndex() {
            return index;
        }

        public void setIndex(String index) {
            this.index = index;
        }
    }
}
