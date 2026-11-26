package com.lynra.kafkatower.agents.specialists.config;

import com.embabel.agent.api.annotation.LlmTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.lynra.kafkatower.collections.CollectionPresenter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/*
 * Deliberately a plain object, not a Spring bean: PromQlMetricsBackend builds the tools from it
 * with Tool.fromInstance. Oversized results are capped by ToolResultLimiter, guided by PromQlTruncationAdvice.
 */
public class PromQlQueryTools {

    static final String METRICS_TOOL = "metrics";
    static final String QUERY_TOOL = "query";
    static final String QUERY_RANGE_TOOL = "query_range";
    static final String LABELS_TOOL = "labels";
    static final String LABEL_VALUES_TOOL = "label_values";
    static final Set<String> TOOL_NAMES =
            Set.of(METRICS_TOOL, QUERY_TOOL, QUERY_RANGE_TOOL, LABELS_TOOL, LABEL_VALUES_TOOL);

    private static final Logger log = LoggerFactory.getLogger(PromQlQueryTools.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PromQlClient client;
    private final CollectionPresenter presenter;

    public PromQlQueryTools(PromQlClient client, CollectionPresenter presenter) {
        this.client = client;
        this.presenter = presenter;
    }

    @LlmTool(name = METRICS_TOOL, description = """
            Discover metric names. Returns the names matching a case-insensitive regular
            expression. Use the narrowest pattern that covers the question,
            e.g. "kminion_kafka_consumer_group.*lag". More than 5 names come back as a snippet
            with a collection key for the bulkOperation tool.
            Parameter match: regular expression to match metric names against; empty for all.
            """)
    public String metrics(String match) {
        return run(METRICS_TOOL, () -> {
            JsonNode data = MAPPER.readTree(client.get("/api/v1/label/__name__/values", "")).path("data");
            Pattern pattern = (match == null || match.isBlank())
                    ? null : Pattern.compile(match, Pattern.CASE_INSENSITIVE);
            List<String> names = new ArrayList<>();
            for (JsonNode name : data) {
                if (pattern == null || pattern.matcher(name.asText()).find()) names.add(name.asText());
            }
            if (names.isEmpty()) {
                return pattern == null
                        ? "The metrics store holds no metric names at all."
                        : "No metric names match '" + match + "'. Do not retry with variations of this pattern: "
                          + "if even a broad pattern like 'kafka' finds nothing, the store holds no such "
                          + "series — report that as a finding.";
            }
            return presenter.block(pattern == null ? "Metric names" : "Metric names matching '" + match + "'", names);
        });
    }

    @LlmTool(name = QUERY_TOOL, description = """
            Run an instant PromQL query. Returns the result type and the series found; more than
            5 series come back as a snippet with a collection key for the bulkOperation tool.
            This is the default way to read a metric. For a trend over time use query_range.
            Parameter query: the PromQL expression.
            Parameter time: evaluation timestamp (RFC3339 or unix seconds); empty for now.
            """)
    public String query(String query, String time) {
        return run(QUERY_TOOL, () -> presentSeries(client.post("/api/v1/query", form("query", query, "time", time))));
    }

    @LlmTool(name = QUERY_RANGE_TOOL, description = """
            Run a PromQL range query. Returns the result type and the series found, each with its
            samples; more than 5 series come back as a snippet with a collection key for the
            bulkOperation tool.
            Use only when a trend or history is explicitly requested.
            Parameter query: the PromQL expression.
            Parameter start: range start (RFC3339 or unix seconds).
            Parameter end: range end (RFC3339 or unix seconds).
            Parameter step: resolution step, e.g. "30s", "10m".
            """)
    public String queryRange(String query, String start, String end, String step) {
        return run(QUERY_RANGE_TOOL, () -> presentSeries(client.post("/api/v1/query_range",
                form("query", query, "start", start, "end", end, "step", step))));
    }

    @LlmTool(name = LABELS_TOOL, description = """
            List label names. More than 5 come back as a snippet with a collection key for the
            bulkOperation tool.
            Parameter match: optional series selector restricting the labels returned; empty for all.
            """)
    public String labels(String match) {
        return run(LABELS_TOOL, () -> presentValues(client.get("/api/v1/labels", matchParam(match)), "Label names"));
    }

    @LlmTool(name = LABEL_VALUES_TOOL, description = """
            List the values of one label. More than 5 come back as a snippet with a collection key
            for the bulkOperation tool.
            Parameter label: label name, e.g. "topic".
            Parameter match: optional series selector restricting the values returned; empty for all.
            """)
    public String labelValues(String label, String match) {
        return run(LABEL_VALUES_TOOL, () -> presentValues(
                client.get("/api/v1/label/" + encode(label) + "/values", matchParam(match)), "Values of label '" + label + "'"));
    }

    private String presentSeries(String responseBody) {
        JsonNode data = parse(responseBody).path("data");
        if (!data.path("result").isArray()) return responseBody;
        return "resultType: " + data.path("resultType").asText() + "\n"
                + presenter.block("Series", rows(data.path("result")));
    }

    private String presentValues(String responseBody, String title) {
        JsonNode data = parse(responseBody).path("data");
        if (!data.isArray()) return responseBody;
        return presenter.block(title, rows(data));
    }

    private static JsonNode parse(String responseBody) {
        try {
            return MAPPER.readTree(responseBody);
        } catch (Exception e) {
            return MissingNode.getInstance();
        }
    }

    private static List<String> rows(JsonNode array) {
        List<String> rows = new ArrayList<>();
        array.forEach(element -> rows.add(element.isTextual() ? element.asText() : element.toString()));
        return rows;
    }

    private interface Call {
        String execute() throws Exception;
    }

    private String run(String tool, Call call) {
        try {
            return call.execute();
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
}
