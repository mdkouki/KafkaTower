package com.lynra.kafkatower.agents.specialists.config;

import com.embabel.agent.api.annotation.LlmTool;
import com.lynra.kafkatower.collections.CollectionPresenter;
import com.lynra.kafkatower.collections.CollectionStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PromQlQueryToolsTest {

    private HttpServer server;
    private volatile int status = 200;
    private volatile String body = "{\"status\":\"success\",\"data\":[]}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
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

    private static CollectionPresenter presenter() {
        return new CollectionPresenter(new CollectionStore(600, 200_000), 5);
    }

    private PromQlQueryTools tools() {
        return new PromQlQueryTools(new PromQlClient(url(), "", "", "", ""), presenter());
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void longLabelListsComeBackAsASnippetWithACollectionKey() {
        body = "{\"status\":\"success\",\"data\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\"]}";

        String result = tools().labels("");

        assertThat(result).contains("Label names (7):").contains("2 more not shown").containsPattern("(?i)collection key: [0-9a-f]+");
    }

    @Test
    void aFailingEndpointBecomesAnErrorResultInsteadOfAnException() {
        status = 500;

        assertThat(tools().labels("")).startsWith("Error:").contains("HTTP 500");
    }

    @Test
    void everyToolIsCoveredByTheTruncationAdvice() {
        Set<String> declaredTools = Arrays.stream(PromQlQueryTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(LlmTool.class))
                .filter(annotation -> annotation != null)
                .map(LlmTool::name)
                .collect(Collectors.toSet());

        assertThat(new PromQlTruncationAdvice().toolNames()).isEqualTo(declaredTools);
    }
}
