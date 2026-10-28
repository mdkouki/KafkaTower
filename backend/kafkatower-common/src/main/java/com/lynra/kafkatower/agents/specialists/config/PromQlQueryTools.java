package com.lynra.kafkatower.agents.specialists.config;

import com.embabel.agent.api.annotation.LlmTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynra.kafkatower.utils.ToolResponseTruncator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Queries any Prometheus-compatible server (VictoriaMetrics, Prometheus, Thanos, Mimir...)
 * directly over its HTTP API ({@code /api/v1/*}) and exposes five tools — {@code metrics},
 * {@code query}, {@code query_range}, {@code labels}, {@code label_values} — so the skills are
 * written once, whatever store sits behind the URL.
 * <p>
 * Deliberately a plain object rather than a Spring bean: {@link PromQlMetricsBackend} builds
 * the tools from it with {@code Tool.fromInstance}, and oversized results are capped here with
 * {@link ToolResponseTruncator}.
 */
public class PromQlQueryTools {

    private static final Logger log = LoggerFactory.getLogger(PromQlQueryTools.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_METRIC_NAMES = 200;
    private static final String TRUNCATION_ADVICE = "Narrow your query — use an instant query "
            + "instead of a range, add a coarser step, a shorter time window, or a more specific "
            + "series filter.";

    private final String baseUrl;
    private final String authHeader;
    private final String tenant;
    private final HttpClient httpClient;

    public PromQlQueryTools(String url, String token, String username, String password, String tenant) {
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.tenant = tenant == null ? "" : tenant;
        if (token != null && !token.isBlank()) {
            this.authHeader = "Bearer " + token;
        } else if (username != null && !username.isBlank()) {
            this.authHeader = "Basic " + Base64.getEncoder().encodeToString(
                    (username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
        } else {
            this.authHeader = null;
        }
        this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        log.info("Metrics backend: url={}, auth={}", baseUrl,
                authHeader == null ? "none" : authHeader.startsWith("Bearer") ? "bearer" : "basic");
    }

    /** Cheap reachability probe: lists label names, which every Prometheus-compatible server serves. */
    public boolean ping() {
        try {
            JsonNode body = MAPPER.readTree(get("/api/v1/labels", ""));
            return "success".equals(body.path("status").asText());
        } catch (Exception e) {
            log.warn("Metrics health check failed: {}", e.getMessage());
            return false;
        }
    }

    @LlmTool(name = "metrics", description = """
            Discover metric names. Returns the names matching a case-insensitive regular
            expression (at most 200). Use the narrowest pattern that covers the question,
            e.g. "kminion_kafka_consumer_group.*lag".
            Parameter match: regular expression to match metric names against; empty for all.
            """)
    public String metrics(String match) {
        return run("metrics", () -> {
            JsonNode data = MAPPER.readTree(get("/api/v1/label/__name__/values", "")).path("data");
            Pattern pattern = (match == null || match.isBlank())
                    ? null : Pattern.compile(match, Pattern.CASE_INSENSITIVE);
            List<String> names = new ArrayList<>();
            int total = 0;
            for (JsonNode name : data) {
                if (pattern == null || pattern.matcher(name.asText()).find()) {
                    total++;
                    if (names.size() < MAX_METRIC_NAMES) names.add(name.asText());
                }
            }
            String note = total > names.size()
                    ? "\n(" + total + " metrics match; showing the first " + names.size() + " — use a narrower pattern.)"
                    : "";
            return String.join("\n", names) + note;
        });
    }

    @LlmTool(name = "query", description = """
            Run an instant PromQL query and return the result as the Prometheus API JSON.
            This is the default way to read a metric. For a trend over time use query_range.
            Parameter query: the PromQL expression.
            Parameter time: evaluation timestamp (RFC3339 or unix seconds); empty for now.
            """)
    public String query(String query, String time) {
        return run("query", () -> post("/api/v1/query", form("query", query, "time", time)));
    }

    @LlmTool(name = "query_range", description = """
            Run a PromQL range query and return the result as the Prometheus API JSON.
            Use only when a trend or history is explicitly requested.
            Parameter query: the PromQL expression.
            Parameter start: range start (RFC3339 or unix seconds).
            Parameter end: range end (RFC3339 or unix seconds).
            Parameter step: resolution step, e.g. "30s", "10m".
            """)
    public String queryRange(String query, String start, String end, String step) {
        return run("query_range", () -> post("/api/v1/query_range",
                form("query", query, "start", start, "end", end, "step", step)));
    }

    @LlmTool(name = "labels", description = """
            List label names.
            Parameter match: optional series selector restricting the labels returned; empty for all.
            """)
    public String labels(String match) {
        return run("labels", () -> get("/api/v1/labels", matchParam(match)));
    }

    @LlmTool(name = "label_values", description = """
            List the values of one label.
            Parameter label: label name, e.g. "topic".
            Parameter match: optional series selector restricting the values returned; empty for all.
            """)
    public String labelValues(String label, String match) {
        return run("label_values", () -> get("/api/v1/label/" + encode(label) + "/values", matchParam(match)));
    }

    private interface Call {
        String execute() throws Exception;
    }

    private String run(String tool, Call call) {
        try {
            String result = call.execute();
            return ToolResponseTruncator.truncateIfNeeded(tool, result, TRUNCATION_ADVICE);
        } catch (Exception e) {
            log.error("Metrics tool {} failed: {}", tool, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    private static String matchParam(String match) {
        return (match == null || match.isBlank()) ? "" : "match[]=" + encode(match);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String form(String... keyValuePairs) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            String value = keyValuePairs[i + 1];
            if (value == null || value.isBlank()) continue;
            if (!body.isEmpty()) body.append('&');
            body.append(keyValuePairs[i]).append('=').append(encode(value));
        }
        return body.toString();
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

    private String get(String path, String queryString) throws Exception {
        String target = queryString.isEmpty() ? path : path + "?" + queryString;
        return send(request(target).GET().build());
    }

    private String post(String path, String formBody) throws Exception {
        return send(request(path)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                .build());
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
