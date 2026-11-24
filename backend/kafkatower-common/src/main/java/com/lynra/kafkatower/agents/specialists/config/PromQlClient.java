package com.lynra.kafkatower.agents.specialists.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/** Authenticated HTTP access to a Prometheus-compatible query API. */
public class PromQlClient {

    private static final Logger log = LoggerFactory.getLogger(PromQlClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final String authHeader;
    private final String tenant;
    private final HttpClient httpClient;

    public PromQlClient(String url, String token, String username, String password, String tenant) {
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.tenant = tenant == null ? "" : tenant;
        this.authHeader = authorizationHeader(token, username, password);
        this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        log.info("Metrics backend: url={}, auth={}", baseUrl,
                authHeader == null ? "none" : authHeader.startsWith("Bearer") ? "bearer" : "basic");
    }

    private static String authorizationHeader(String token, String username, String password) {
        if (token != null && !token.isBlank()) {
            return "Bearer " + token;
        }
        if (username != null && !username.isBlank()) {
            String credentials = username + ":" + (password == null ? "" : password);
            return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        }
        return null;
    }

    public boolean ping() {
        String error = probe();
        if (error != null) {
            log.warn("Metrics health check failed: {}", error);
        }
        return error == null;
    }

    /** Lists label names, which every Prometheus-compatible server serves; returns the failure reason or null. */
    public String probe() {
        try {
            JsonNode body = MAPPER.readTree(get("/api/v1/labels", ""));
            return "success".equals(body.path("status").asText())
                    ? null : "Unexpected answer from " + baseUrl + ": " + body.path("error").asText("no success status");
        } catch (Exception e) {
            return e.getMessage() == null || e.getMessage().isBlank()
                    ? "Connection failed (" + e.getClass().getSimpleName() + ")" : e.getMessage();
        }
    }

    public String get(String path, String queryString) throws Exception {
        String target = queryString.isEmpty() ? path : path + "?" + queryString;
        return send(request(target).GET().build());
    }

    public String post(String path, String formBody) throws Exception {
        return send(request(path)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                .build());
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .header("Accept", "application/json");
        if (authHeader != null) builder.header("Authorization", authHeader);
        if (!tenant.isBlank()) builder.header("X-Scope-OrgID", tenant);
        return builder;
    }

    private String send(HttpRequest request) throws Exception {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            String body = response.body() == null ? "" : response.body();
            throw new IllegalStateException("HTTP " + response.statusCode() + " from "
                    + request.uri().getPath() + ": " + body.substring(0, Math.min(body.length(), 500)));
        }
        return response.body();
    }
}
