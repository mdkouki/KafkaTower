package com.lynra.kafkatower.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks LLM settings against the real endpoint before they are saved: URL reachable, key
 * accepted, model served. Lists {@code /models}; endpoints that do not offer it get a minimal
 * chat completion instead.
 */
@Component
public class LlmConnectionVerifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** @throws IllegalArgumentException with a user-facing reason when the settings do not work */
    public void verify(String baseUrl, String apiKey, String model) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        try {
            HttpResponse<String> models = send(base + "/models", apiKey, null);
            int status = models.statusCode();
            if (status == 401 || status == 403) {
                throw new IllegalArgumentException("The endpoint rejected the API key (HTTP " + status + ").");
            }
            if (status / 100 == 2) {
                List<String> ids = new ArrayList<>();
                for (JsonNode m : MAPPER.readTree(models.body()).path("data")) {
                    ids.add(m.path("id").asText());
                }
                if (!ids.isEmpty() && !ids.contains(model)) {
                    throw new IllegalArgumentException("The endpoint does not serve model '" + model
                            + "'. Available: " + String.join(", ", ids));
                }
                return;
            }
            probeCompletion(base, apiKey, model);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IllegalArgumentException("The endpoint did not answer in time: " + baseUrl);
        } catch (Exception e) {
            String reason = e.getMessage() == null || e.getMessage().isBlank() ? "connection failed (" + e.getClass().getSimpleName() + ")" : e.getMessage();
            throw new IllegalArgumentException("Cannot reach " + baseUrl + ": " + reason);
        }
    }

    private void probeCompletion(String base, String apiKey, String model) throws Exception {
        String body = MAPPER.writeValueAsString(java.util.Map.of("model", model,
                "messages", List.of(java.util.Map.of("role", "user", "content", "ping")),
                "max_completion_tokens", 16));
        HttpResponse<String> response = send(base + "/chat/completions", apiKey, body);
        if (response.statusCode() / 100 != 2) {
            throw new IllegalArgumentException("The endpoint refused a test request for model '" + model
                    + "' (HTTP " + response.statusCode() + "): " + abbreviate(response.body()));
        }
    }

    private HttpResponse<String> send(String url, String apiKey, String jsonBody) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(TIMEOUT)
                .header("Authorization", "Bearer " + apiKey).header("Accept", "application/json");
        if (jsonBody == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String abbreviate(String text) {
        return text == null ? "" : text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }
}
