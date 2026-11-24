package com.lynra.kafkatower.tools;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lynra.kafkatower.agents.specialists.config.OpenSearchClientProvider;
import com.lynra.kafkatower.agents.specialists.config.OpenSearchClientRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.Optional;

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

    private OpenSearchQueryTools tools;
    private ListAppender<ILoggingEvent> appender;
    private Logger logbackLogger;

    @BeforeEach
    void setUp() {
        tools = new OpenSearchQueryTools(registry);
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
                "{\"took\":3,\"timed_out\":false,\"_shards\":{},\"hits\":{\"hits\":["
                        + "{\"_index\":\"i\",\"_type\":\"t\",\"_score\":1.0,\"_id\":\"1\",\"_source\":{\"a\":1}}]}}");

        String result = tools.searchDocuments("prod", "", "{}");

        assertThat(result).doesNotContain("took", "timed_out", "_shards", "_index", "_type", "_score")
                .contains("\"_id\":\"1\"", "\"_source\":{\"a\":1}");
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
    void aggregateDocumentsForcesSizeZeroAndKeepsOnlyAggregationsAndTotal() throws Exception {
        OpenSearchClientProvider provider = providerWithDefaultIndex("logs-*");
        when(provider.post(anyString(), anyString())).thenReturn(
                "{\"took\":1,\"hits\":{\"total\":{\"value\":5},\"hits\":[{\"_id\":\"1\"}]},"
                        + "\"aggregations\":{\"by_level\":{\"buckets\":[]}}}");

        String result = tools.aggregateDocuments("prod", "", "{\"size\":50,\"aggs\":{}}");

        var body = forClass(String.class);
        verify(provider).post(eq("/logs-*/_search"), body.capture());
        assertThat(body.getValue()).contains("\"size\":0").doesNotContain("50");
        assertThat(result).isEqualTo("{\"totalHits\":{\"value\":5},\"aggregations\":{\"by_level\":{\"buckets\":[]}}}");
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
