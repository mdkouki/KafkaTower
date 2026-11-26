package com.lynra.kafkatower.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmConnectionVerifierTest {

    private final LlmConnectionVerifier verifier = new LlmConnectionVerifier();
    private final List<String> requests = new ArrayList<>();
    private HttpServer server;
    private volatile int modelsStatus = 200;
    private volatile String modelsBody = "{\"data\":[{\"id\":\"gpt-x\"},{\"id\":\"gpt-y\"}]}";
    private volatile int completionStatus = 200;
    private volatile String completionBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"pong\"}}]}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            requests.add(exchange.getRequestMethod() + " /v1/models auth=" + exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, modelsStatus, modelsBody);
        });
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(exchange.getRequestMethod() + " /v1/chat/completions "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, completionStatus, completionBody);
        });
        server.createContext("/", exchange -> reply(exchange, 200,
                "{\"error\":\"Unexpected endpoint or method. (" + exchange.getRequestMethod() + " "
                        + exchange.getRequestURI().getPath() + "). Returning 200 anyway\"}"));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @Test
    void acceptsAModelListedByTheEndpointAndSendsTheApiKey() {
        assertThatCode(() -> verifier.verify(baseUrl(), "sk-secret", "gpt-x")).doesNotThrowAnyException();

        assertThat(requests).containsExactly("GET /v1/models auth=Bearer sk-secret");
    }

    @Test
    void ignoresATrailingSlashOnTheBaseUrl() {
        assertThatCode(() -> verifier.verify(baseUrl() + "/", "k", "gpt-x")).doesNotThrowAnyException();
    }

    @Test
    void rejectsAModelTheEndpointDoesNotServeAndListsTheAvailableOnes() {
        assertThatThrownBy(() -> verifier.verify(baseUrl(), "k", "missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not serve model 'missing'")
                .hasMessageContaining("gpt-x, gpt-y");
    }

    @Test
    void acceptsAnyModelWhenTheEndpointListsNone() {
        modelsBody = "{\"data\":[]}";

        assertThatCode(() -> verifier.verify(baseUrl(), "k", "anything")).doesNotThrowAnyException();
    }

    @Test
    void rejectsAnApiKeyTheEndpointRefuses() {
        modelsStatus = 401;

        assertThatThrownBy(() -> verifier.verify(baseUrl(), "bad", "gpt-x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The endpoint rejected the API key (HTTP 401).");
    }

    @Test
    void fallsBackToATestCompletionWhenTheEndpointHasNoModelListing() {
        modelsStatus = 404;

        assertThatCode(() -> verifier.verify(baseUrl(), "k", "gpt-x")).doesNotThrowAnyException();

        assertThat(requests).hasSize(2);
        assertThat(requests.get(1)).startsWith("POST /v1/chat/completions").contains("\"model\":\"gpt-x\"");
    }

    @Test
    void reportsARefusedTestCompletionWithAPreviewOfTheBody() {
        modelsStatus = 404;
        completionStatus = 400;
        completionBody = "unknown model";

        assertThatThrownBy(() -> verifier.verify(baseUrl(), "k", "gpt-x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refused a test request for model 'gpt-x' (HTTP 400): unknown model");
    }

    @Test
    void abbreviatesLongErrorBodies() {
        modelsStatus = 404;
        completionStatus = 500;
        completionBody = "e".repeat(1_000);

        assertThatThrownBy(() -> verifier.verify(baseUrl(), "k", "gpt-x"))
                .hasMessageContaining("e".repeat(300) + "…")
                .hasMessageNotContaining("e".repeat(301));
    }

    @Test
    void reportsAnUnreachableEndpoint() {
        int unusedPort = server.getAddress().getPort();
        server.stop(0);

        assertThatThrownBy(() -> verifier.verify("http://127.0.0.1:" + unusedPort + "/v1", "k", "gpt-x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Cannot reach http://127.0.0.1:" + unusedPort + "/v1: ");
    }

    @Test
    void rejectsABaseUrlMissingTheVersionPathThatTheEndpointAnswersWith200AndAnErrorBody() {
        String withoutVersionPath = baseUrl().substring(0, baseUrl().length() - "/v1".length());

        assertThatThrownBy(() -> verifier.verify(withoutVersionPath, "k", "gpt-x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a chat completion")
                .hasMessageContaining("/v1")
                .hasMessageContaining("Unexpected endpoint or method. (POST /chat/completions)");
    }

    @Test
    void rejectsAModelListingThatIsAnErrorBodyAndWhoseTestCompletionIsNotACompletion() {
        modelsBody = "{\"error\":\"nope\"}";
        completionBody = "{\"error\":\"nope\"}";

        assertThatThrownBy(() -> verifier.verify(baseUrl(), "k", "gpt-x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a chat completion");
    }
}
