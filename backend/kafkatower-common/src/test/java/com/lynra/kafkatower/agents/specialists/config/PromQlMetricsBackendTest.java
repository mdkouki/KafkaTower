package com.lynra.kafkatower.agents.specialists.config;

import com.embabel.agent.api.tool.progressive.UnfoldingTool;
import com.lynra.kafkatower.collections.CollectionPresenter;
import com.lynra.kafkatower.collections.CollectionStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PromQlMetricsBackendTest {

    private HttpServer server;
    private final AtomicReference<String> lastRequest = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "{\"status\":\"success\",\"data\":[]}";
    private final CollectionStore store = new CollectionStore(600, 200_000);
    private final CollectionPresenter presenter = new CollectionPresenter(store, 5);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastRequest.set(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " + requestBody);
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private PromQlClient client(String token) {
        return new PromQlClient("http://127.0.0.1:" + server.getAddress().getPort(), token, "", "", "");
    }

    private PromQlQueryTools tools(String token) {
        return new PromQlQueryTools(client(token), presenter);
    }

    @Test
    void exposesTheFiveMetricsTools() {
        PromQlMetricsBackend backend = new PromQlMetricsBackend(
                "http://127.0.0.1:" + server.getAddress().getPort(), "", "", "", "", presenter);

        UnfoldingTool facade = backend.queryTools("metrics-query", "Kafka data", "Start with `metrics`.");

        List<String> names = new ArrayList<>();
        facade.getInnerTools().forEach(t -> names.add(t.getDefinition().getName()));
        assertThat(names).containsExactlyInAnyOrder("metrics", "query", "query_range", "labels", "label_values");
        assertThat(facade.getDefinition().getName()).isEqualTo("metrics-query");
    }

    @Test
    void instantQueryIsPostedAsAForm() {
        body = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}";

        String result = tools("").query("up{cluster=\"a\"}", "");

        assertThat(result).isEqualTo("resultType: vector\nSeries (0): (none)\n");
        assertThat(lastRequest.get()).startsWith("POST /api/v1/query ").contains("query=up%7Bcluster%3D%22a%22%7D")
                .doesNotContain("time=");
    }

    @Test
    void rangeQueryForwardsStartEndAndStep() {
        tools("").queryRange("up", "1700000000", "1700003600", "30s");

        assertThat(lastRequest.get()).startsWith("POST /api/v1/query_range ")
                .contains("start=1700000000", "end=1700003600", "step=30s");
    }

    @Test
    void labelValuesHitsTheLabelEndpointWithOptionalMatch() {
        tools("").labelValues("topic", "kafka_topic_partitions{cluster=\"a\"}");

        assertThat(lastRequest.get()).startsWith("GET /api/v1/label/topic/values?match[]=kafka_topic_partitions");
    }

    @Test
    void metricsFiltersNamesByCaseInsensitiveRegex() {
        body = "{\"status\":\"success\",\"data\":[\"kafka_brokers\",\"KMINION_lag\",\"up\"]}";

        assertThat(tools("").metrics("kminion|brokers"))
                .isEqualTo("Metric names matching 'kminion|brokers' (2):\n  - kafka_brokers\n  - KMINION_lag\n");
    }

    @Test
    void metricsSaysSoWhenNothingMatchesInsteadOfReturningEmptyText() {
        body = "{\"status\":\"success\",\"data\":[\"up\"]}";

        assertThat(tools("").metrics("kafka_.*")).contains("No metric names match 'kafka_.*'").doesNotStartWith("\n");
    }

    private static final Pattern KEY = Pattern.compile("Collection key: ([0-9a-f]+)");

    private List<String> storedList(String output) {
        Matcher matcher = KEY.matcher(output);
        assertThat(matcher.find()).as("a collection key in: " + output).isTrue();
        return store.find(matcher.group(1)).orElseThrow();
    }

    @Test
    void metricsBeyondTheInlineLimitComeBackAsASnippetWithAKeyToTheFullList() {
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < 250; i++) names.append(i == 0 ? "" : ",").append("\"m_").append(i).append('"');
        body = "{\"status\":\"success\",\"data\":[" + names + "]}";

        String result = tools("").metrics("m_");

        assertThat(result).startsWith("Metric names matching 'm_' (250):\n  - m_0\n").contains("  - m_4\n")
                .doesNotContain("m_5\n").contains("245 more not shown");
        assertThat(storedList(result)).hasSize(250).startsWith("m_0", "m_1").endsWith("m_249");
    }

    @Test
    void labelValuesBeyondTheInlineLimitAreStoredToo() {
        body = "{\"status\":\"success\",\"data\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}";

        String result = tools("").labelValues("topic", "");

        assertThat(result).startsWith("Values of label 'topic' (6):").contains("1 more not shown");
        assertThat(storedList(result)).containsExactly("a", "b", "c", "d", "e", "f");
    }

    @Test
    void queryWithFewSeriesListsThemAllWithoutAKey() {
        body = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                + "{\"metric\":{\"topic\":\"a\"},\"value\":[1,\"7\"]}]}}";

        assertThat(tools("").query("up", "")).isEqualTo(
                "resultType: vector\nSeries (1):\n  - {\"metric\":{\"topic\":\"a\"},\"value\":[1,\"7\"]}\n");
    }

    @Test
    void queryWithManySeriesStoresTheWholeResult() {
        StringBuilder series = new StringBuilder();
        for (int i = 0; i < 8; i++) series.append(i == 0 ? "" : ",").append("{\"metric\":{\"p\":\"").append(i).append("\"}}");
        body = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[" + series + "]}}";

        String result = tools("").queryRange("up", "1", "2", "30s");

        assertThat(result).startsWith("resultType: matrix\nSeries (8):").contains("3 more not shown");
        assertThat(storedList(result)).hasSize(8).contains("{\"metric\":{\"p\":\"7\"}}");
    }

    @Test
    void httpErrorsBecomeAnErrorStringInsteadOfThrowing() {
        status = 400;
        body = "{\"status\":\"error\",\"error\":\"bad expr\"}";

        assertThat(tools("").query("sum(", "")).startsWith("Error: HTTP 400").contains("bad expr");
    }

    @Test
    void bearerTokenIsSent() {
        client("secret-token").ping();

        assertThat(lastAuth.get()).isEqualTo("Bearer secret-token");
    }

    @Test
    void pingReflectsReachability() {
        assertThat(client("").ping()).isTrue();

        status = 500;
        assertThat(client("").ping()).isFalse();
    }
}
