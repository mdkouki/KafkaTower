package com.lynra.kafkatower.tools;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import static org.mockito.Mockito.when;

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
}
