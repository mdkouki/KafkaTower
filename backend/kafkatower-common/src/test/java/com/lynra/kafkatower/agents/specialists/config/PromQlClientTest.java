package com.lynra.kafkatower.agents.specialists.config;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PromQlClientTest {

    private HttpServer server;
    private final AtomicReference<Headers> lastHeaders = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private volatile String body = "{\"status\":\"success\",\"data\":[]}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastHeaders.set(exchange.getRequestHeaders());
            lastPath.set(exchange.getRequestURI().toString());
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

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void basicCredentialsAreSentWhenNoTokenIsGiven() {
        new PromQlClient(url(), "", "alice", "s3cret", "").ping();

        String expected = "Basic " + Base64.getEncoder().encodeToString("alice:s3cret".getBytes(StandardCharsets.UTF_8));
        assertThat(lastHeaders.get().getFirst("Authorization")).isEqualTo(expected);
    }

    @Test
    void tokenTakesPrecedenceOverBasicCredentials() {
        new PromQlClient(url(), "tok", "alice", "s3cret", "").ping();

        assertThat(lastHeaders.get().getFirst("Authorization")).isEqualTo("Bearer tok");
    }

    @Test
    void noAuthorizationHeaderIsSentWithoutCredentials() {
        new PromQlClient(url(), null, null, null, null).ping();

        assertThat(lastHeaders.get().getFirst("Authorization")).isNull();
    }

    @Test
    void tenantIsSentAsScopeOrgIdHeader() {
        new PromQlClient(url(), "", "", "", "team-a").ping();

        assertThat(lastHeaders.get().getFirst("X-Scope-OrgID")).isEqualTo("team-a");
    }

    @Test
    void trailingSlashOfTheBaseUrlDoesNotProduceADoubleSlash() {
        new PromQlClient(url() + "/", "", "", "", "").ping();

        assertThat(lastPath.get()).isEqualTo("/api/v1/labels");
    }

    @Test
    void probeReturnsNullWhenTheBackendAnswersWithSuccess() {
        assertThat(new PromQlClient(url(), "", "", "", "").probe()).isNull();
    }

    @Test
    void probeExplainsAnUnsuccessfulAnswer() {
        body = "{\"status\":\"error\",\"error\":\"boom\"}";

        assertThat(new PromQlClient(url(), "", "", "", "").probe())
                .startsWith("Unexpected answer from " + url()).endsWith("boom");
    }

    @Test
    void probeReportsAnUnreachableBackend() {
        String unreachable = url();
        server.stop(0);

        assertThat(new PromQlClient(unreachable, "", "", "", "").probe()).isNotBlank();
    }
}
