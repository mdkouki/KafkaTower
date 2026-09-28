package com.lynra.kafkatower.agents.specialists.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Builds one {@link OpenSearchClientProvider} per entry in {@code agent.mcp.os.clusters}
 * (one direct OpenSearch REST connection per Kafka cluster). Active only when
 * {@code agent.mcp.os.enabled=true}.
 */
@Component
@EnableConfigurationProperties(OsProperties.class)
@ConditionalOnProperty(prefix = "agent.mcp.os", name = "enabled", havingValue = "true")
public class OpenSearchClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchClientRegistry.class);

    private final Map<String, OpenSearchClientProvider> providers;
    private final String defaultCluster;

    public OpenSearchClientRegistry(OsProperties properties) {
        this.providers = properties.getClusters().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> new OpenSearchClientProvider(e.getKey(), e.getValue().getUrl(),
                                e.getValue().getUsername(), e.getValue().getPassword(), e.getValue().getIndex())
                ));
        this.defaultCluster = properties.getDefaultCluster();
        log.info("OpenSearchClientRegistry initialized with {} cluster(s): {} (default: {}). " +
                        "These keys must match the ones the log-inspection agent resolves against — " +
                        "a mismatch (e.g. a different name/casing than the live Kafka cluster) " +
                        "will silently route to the wrong or no OpenSearch connection.",
                providers.size(), providers.keySet(), defaultCluster != null ? defaultCluster : "none");
    }

    public Optional<OpenSearchClientProvider> forCluster(String clusterName) {
        return Optional.ofNullable(providers.get(clusterName));
    }

    /** Cluster names with a configured OpenSearch connection, sorted for stable prompt text. */
    public TreeSet<String> clusterNames() {
        return new TreeSet<>(providers.keySet());
    }

    /** The configured default cluster, if any, used when the question doesn't name one. */
    public Optional<String> defaultCluster() {
        if (defaultCluster != null && providers.containsKey(defaultCluster)) {
            return Optional.of(defaultCluster);
        }
        return Optional.empty();
    }

    public boolean isEmpty() {
        return providers.isEmpty();
    }
}
