package com.lynra.kafkatower.agents.specialists.config;

import com.lynra.kafkatower.collections.CollectionPresenter;
import com.lynra.kafkatower.collections.CollectionStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PromQlQueryToolsTest {

    private HttpServer server;
    private volatile String body = "{\"status\":\"success\",\"data\":[]}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
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

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void oversizedResultsAreTruncatedWithQueryNarrowingAdvice() {
        body = "{\"status\":\"success\",\"data\":\"" + "x".repeat(10_000) + "\"}";

        String result = new PromQlQueryTools(new PromQlClient(url(), "", "", "", ""), presenter()).labels("");

        assertThat(result).contains("[TRUNCATED:").contains("use an instant query instead of a range");
    }

    @Test
    void emptyResultsBecomeAPlaceholder() {
        body = " ";

        assertThat(new PromQlQueryTools(new PromQlClient(url(), "", "", "", ""), presenter()).labels("")).isEqualTo("(the tool returned no output)");
    }
}
