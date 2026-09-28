package com.lynra.kafkatower.agents.specialists.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Owns one direct OpenSearch REST connection for a single Kafka cluster. One instance is
 * created per entry under {@code agent.mcp.os.clusters} by {@link OpenSearchClientRegistry}.
 * Authenticates with HTTP Basic auth (login/password) — used instead of OpenSearch's native
 * MCP server because the OpenSearch version deployed here does not support it.
 */
public class OpenSearchClientProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchClientProvider.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final String clusterName;
    private final String baseUrl;
    private final String defaultIndex;
    private final String authHeader;
    private final HttpClient httpClient;

    public OpenSearchClientProvider(String clusterName, String url, String username, String password, String defaultIndex) {
        this.clusterName = clusterName;
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.defaultIndex = defaultIndex;
        this.authHeader = (username == null || username.isBlank())
                ? null
                : "Basic " + Base64.getEncoder().encodeToString(
                        (username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        log.info("OpenSearchClientProvider created for cluster '{}': baseUrl={}, index={}, auth={}",
                clusterName, this.baseUrl, defaultIndex, authHeader != null ? "basic" : "none");
    }

    public String clusterName() {
        return clusterName;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String defaultIndex() {
        return defaultIndex;
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .header("Accept", "application/json");
        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }
        return builder;
    }

    public String get(String path) throws Exception {
        HttpResponse<String> response = httpClient.send(
                request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
        return checkStatus(path, response);
    }

    public String post(String path, String jsonBody) throws Exception {
        HttpResponse<String> response = httpClient.send(
                request(path)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        return checkStatus(path, response);
    }

    private String checkStatus(String path, HttpResponse<String> response) {
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("OpenSearch request to '" + path + "' on cluster '" + clusterName +
                    "' failed with HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    /** Probes with a cluster health call. */
    public boolean isAvailable() {
        String healthUrl = baseUrl + "/_cluster/health";
        try {
            log.debug("OpenSearch health check starting for cluster '{}' at {}", clusterName, healthUrl);
            get("/_cluster/health");
            log.debug("OpenSearch health check succeeded for cluster '{}'", clusterName);
            return true;
        } catch (Exception e) {
            log.warn("OpenSearch health check failed for cluster '{}' at {}: {}: {}",
                    clusterName, healthUrl, e.getClass().getName(), e.getMessage(), e);
            return false;
        }
    }
}
