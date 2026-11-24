package com.lynra.kafkatower.tools;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lynra.kafkatower.agents.specialists.config.OpenSearchClientProvider;
import com.lynra.kafkatower.agents.specialists.config.OpenSearchClientRegistry;
import com.lynra.kafkatower.collections.CollectionPresenter;
import com.lynra.kafkatower.collections.CollectionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.joining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentCaptor.forClass;

@ExtendWith(MockitoExtension.class)
class OpenSearchQueryToolsTest {

    @Mock
    private OpenSearchClientRegistry registry;

    private static final Pattern COLLECTION_KEY = Pattern.compile("Collection key: ([0-9a-f]+)");

    private OpenSearchQueryTools tools;
    private CollectionStore store;
    private ListAppender<ILoggingEvent> appender;
    private Logger logbackLogger;

    @BeforeEach
    void setUp() {
        store = new CollectionStore(600, 200_000);
        tools = new OpenSearchQueryTools(registry, new CollectionPresenter(store, 5));
        logbackLogger = (Logger) LoggerFactory.getLogger(OpenSearchQueryTools.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logbackLogger.detachAppender(appender);
    }

    @Test
    void searchDocumentsLogsAndReturnsErrorForUnknownCluster() {
        when(registry.forCluster("bogus")).thenReturn(Optional.empty());

        String result = tools.searchDocuments("bogus", "", "{}");

        assertThat(result).contains("Error").contains("Unknown OpenSearch cluster: bogus");
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("Unknown OpenSearch cluster: bogus");
        });
    }

    @Test
    void aggregateDocumentsLogsAndReturnsErrorForUnknownCluster() {
        when(registry.forCluster("bogus")).thenReturn(Optional.empty());

        String result = tools.aggregateDocuments("bogus", "", "{\"aggs\":{}}");

        assertThat(result).contains("Error").contains("Unknown OpenSearch cluster: bogus");
        assertThat(appender.list).anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
    }

    @Test
    void getDocumentLogsAndReturnsErrorForUnknownCluster() {
        when(registry.forCluster("bogus")).thenReturn(Optional.empty());

        String result = tools.getDocument("bogus", "", "doc-1");

        assertThat(result).contains("Error").contains("Unknown OpenSearch cluster: bogus");
        assertThat(appender.list).anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
    }

    private OpenSearchClientProvider providerWithDefaultIndex(String defaultIndex) {
        OpenSearchClientProvider provider = mock(OpenSearchClientProvider.class);
        lenient().when(provider.clusterName()).thenReturn("prod");
        lenient().when(provider.defaultIndex()).thenReturn(defaultIndex);
        when(registry.forCluster("prod")).thenReturn(Optional.of(provider));
        return provider;
    }

    @Test
    void searchDocumentsStripsEnvelopeFieldsFromTheResponse() throws Exception {
        OpenSearchClientProvider provider = providerWithDefaultIndex("logs-*");
        when(provider.post(eq("/logs-*/_search"), anyString())).thenReturn(
                "{\"took\":3,\"timed_out\":false,\"_shards\":{},\"hits\":{\"total\":{\"value\":1},\"hits\":["
                        + "{\"_index\":\"i\",\"_type\":\"t\",\"_score\":1.0,\"_id\":\"1\",\"_source\":{\"a\":1}}]}}");

        String result = tools.searchDocuments("prod", "", "{}");

        assertThat(result).doesNotContain("took", "timed_out", "_shards", "_index", "_type", "_score")
                .contains("{\"_id\":\"1\",\"_source\":{\"a\":1}}");
    }

    @Test
    void searchDocumentsPrefersTheExplicitIndexOverTheDefault() throws Exception {
        OpenSearchClientProvider provider = providerWithDefaultIndex("logs-*");
        when(provider.post(anyString(), anyString())).thenReturn("{}");

        tools.searchDocuments("prod", "other-*", "{}");

        verify(provider).post(eq("/other-*/_search"), anyString());
    }

    @Test
    void searchDocumentsReturnsErrorWhenNoIndexIsAvailable() {
        providerWithDefaultIndex(" ");

        String result = tools.searchDocuments("prod", null, "{}");

        assertThat(result).startsWith("Error: ").contains("no default index configured for cluster 'prod'");
    }

    @Test
    void searchDocumentsReturnsErrorWhenOpenSearchFails() throws Exception {
        OpenSearchClientProvider provider = providerWithDefaultIndex("logs-*");
        when(provider.post(anyString(), anyString())).thenThrow(new IllegalStateException("boom"));

        assertThat(tools.searchDocuments("prod", "", "{}")).isEqualTo("Error: boom");
    }

    @Test
    void searchDocumentsReturnsNonJsonResponsesUntouched() throws Exception {
        OpenSearchClientProvider provider = providerWithDefaultIndex("logs-*");
        when(provider.post(anyString(), anyString())).thenReturn("not json");

        assertThat(tools.searchDocuments("prod", "", "{}")).isEqualTo("not json");
    }

    @Test
    void aggregateDocumentsForcesSizeZeroAndReportsTotalAndBuckets() throws Exception {
        OpenSearchClientProvider provider = providerWithDefaultIndex("logs-*");
        when(provider.post(anyString(), anyString())).thenReturn(
                "{\"took\":1,\"hits\":{\"total\":{\"value\":5},\"hits\":[{\"_id\":\"1\"}]},"
                        + "\"aggregations\":{\"by_level\":{\"buckets\":[{\"key\":\"ERROR\",\"doc_count\":4}]}}}");

        String result = tools.aggregateDocuments("prod", "", "{\"size\":50,\"aggs\":{}}");

        var body = forClass(String.class);
        verify(provider).post(eq("/logs-*/_search"), body.capture());
        assertThat(body.getValue()).contains("\"size\":0").doesNotContain("50");
        assertThat(result).isEqualTo("totalHits: 5\n"
                + "Aggregation by_level (1):\n"
                + "  - {\"key\":\"ERROR\",\"doc_count\":4}\n");
    }

    @Test
    void aggregateDocumentsParksMoreThanFiveBucketsBehindACollectionKey() throws Exception {
        OpenSearchClientProvider provider = providerWithDefaultIndex("logs-*");
        when(provider.post(anyString(), anyString())).thenReturn(
                "{\"hits\":{\"total\":{\"value\":70}},\"aggregations\":{\"by_host\":{\"buckets\":["
                        + IntStream.rangeClosed(1, 7).mapToObj(i -> "{\"key\":\"h" + i + "\",\"doc_count\":" + i + "}")
                                .collect(joining(","))
                        + "]}}}");

        String result = tools.aggregateDocuments("prod", "", "{\"aggs\":{}}");

        assertThat(result).startsWith("totalHits: 70\nAggregation by_host (7):\n")
                .contains("{\"key\":\"h5\",\"doc_count\":5}")
                .doesNotContain("h6");
        assertThat(storedList(result)).hasSize(7).first().isEqualTo("{\"key\":\"h1\",\"doc_count\":1}");
    }

    @Test
    void aggregateDocumentsPrintsNonBucketAggregationsOnOneLine() throws Exception {
        OpenSearchClientProvider provider = providerWithDefaultIndex("logs-*");
        when(provider.post(anyString(), anyString())).thenReturn(
                "{\"hits\":{\"total\":{\"value\":3}},\"aggregations\":{\"avg_latency\":{\"value\":12.5}}}");

        assertThat(tools.aggregateDocuments("prod", "", "{\"aggs\":{}}"))
                .isEqualTo("totalHits: 3\navg_latency: {\"value\":12.5}\n");
    }

    @Test
    void aggregateDocumentsReturnsNonJsonResponsesUntouched() throws Exception {
        OpenSearchClientProvider provider = providerWithDefaultIndex("logs-*");
        when(provider.post(anyString(), anyString())).thenReturn("not json");

        assertThat(tools.aggregateDocuments("prod", "", "{\"aggs\":{}}")).isEqualTo("not json");
    }

    @Test
    void searchDocumentsReturnsCompleteDocumentsWithoutKeyWhenFiveOrFewer() throws Exception {
        OpenSearchClientProvider provider = providerWithDefaultIndex("logs-*");
        when(provider.post(anyString(), anyString())).thenReturn(searchResponse(5, 5));

        String result = tools.searchDocuments("prod", "", "{}");

        assertThat(result).startsWith("Total hits: 5\nDocuments (5):\n")
                .contains("{\"_id\":\"1\",\"_source\":{\"n\":1}}", "{\"_id\":\"5\",\"_source\":{\"n\":5}}")
                .doesNotContain("Collection key");
    }

    @Test
    void searchDocumentsParksMoreThanFiveDocumentsBehindACollectionKey() throws Exception {
        OpenSearchClientProvider provider = providerWithDefaultIndex("logs-*");
        when(provider.post(anyString(), anyString())).thenReturn(searchResponse(120, 7));

        String result = tools.searchDocuments("prod", "", "{}");

        assertThat(result).startsWith("Total hits: 120\nDocuments (7):\n")
                .contains("{\"_id\":\"5\",\"_source\":{\"n\":5}}")
                .doesNotContain("\"_id\":\"6\"");
        assertThat(storedList(result)).hasSize(7)
                .first().isEqualTo("{\"_id\":\"1\",\"_source\":{\"n\":1}}");
        assertThat(storedList(result)).last().isEqualTo("{\"_id\":\"7\",\"_source\":{\"n\":7}}");
    }

    @Test
    void searchDocumentsReturnsEmptyDocumentListWhenNothingMatches() throws Exception {
        OpenSearchClientProvider provider = providerWithDefaultIndex("logs-*");
        when(provider.post(anyString(), anyString())).thenReturn(searchResponse(0, 0));

        assertThat(tools.searchDocuments("prod", "", "{}")).isEqualTo("Total hits: 0\nDocuments (0): (none)\n");
    }

    @Test
    void searchDocumentsReturnsBodiesWithoutHitsArrayUntouched() throws Exception {
        OpenSearchClientProvider provider = providerWithDefaultIndex("logs-*");
        when(provider.post(anyString(), anyString())).thenReturn("{\"error\":\"bad\"}");

        assertThat(tools.searchDocuments("prod", "", "{}")).isEqualTo("{\"error\":\"bad\"}");
    }

    private static String searchResponse(int total, int returnedHits) {
        String hits = IntStream.rangeClosed(1, returnedHits)
                .mapToObj(i -> "{\"_index\":\"i\",\"_score\":1.0,\"_id\":\"" + i + "\",\"_source\":{\"n\":" + i + "}}")
                .collect(joining(","));
        return "{\"took\":3,\"hits\":{\"total\":{\"value\":" + total + "},\"hits\":[" + hits + "]}}";
    }

    private List<String> storedList(String output) {
        Matcher key = COLLECTION_KEY.matcher(output);
        assertThat(key.find()).as("collection key in %s", output).isTrue();
        return store.find(key.group(1)).orElseThrow();
    }

    @Test
    void aggregateDocumentsRejectsMalformedQueryJson() {
        providerWithDefaultIndex("logs-*");

        assertThat(tools.aggregateDocuments("prod", "", "[1,2]")).startsWith("Error: invalid queryJson: ");
        assertThat(tools.aggregateDocuments("prod", "", "{broken")).startsWith("Error: invalid queryJson: ");
    }

    @Test
    void getDocumentReturnsOnlyTheSource() throws Exception {
        OpenSearchClientProvider provider = providerWithDefaultIndex("logs-*");
        when(provider.get("/logs-*/_doc/42")).thenReturn("{\"_id\":\"42\",\"found\":true,\"_source\":{\"msg\":\"hi\"}}");

        assertThat(tools.getDocument("prod", "", "42")).isEqualTo("{\"msg\":\"hi\"}");
    }

    @Test
    void getDocumentReturnsTheBodyWhenThereIsNoSource() throws Exception {
        OpenSearchClientProvider provider = providerWithDefaultIndex("logs-*");
        when(provider.get(anyString())).thenReturn("{\"found\":false}");

        assertThat(tools.getDocument("prod", "", "42")).isEqualTo("{\"found\":false}");
    }
}
