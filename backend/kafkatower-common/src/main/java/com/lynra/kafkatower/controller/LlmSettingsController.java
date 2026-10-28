package com.lynra.kafkatower.controller;

import com.lynra.kafkatower.config.ApplicationRestarter;
import com.lynra.kafkatower.model.LlmSettings;
import com.lynra.kafkatower.repository.LlmSettingsRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/llm-settings")
public class LlmSettingsController {

    private final LlmSettingsRepository repo;
    private final ApplicationRestarter restarter;

    public LlmSettingsController(LlmSettingsRepository repo, ApplicationRestarter restarter) {
        this.repo = repo;
        this.restarter = restarter;
    }

    @GetMapping
    public LlmSettingsResponse get() {
        return repo.findById(1L)
                .map(s -> new LlmSettingsResponse(
                        s.getApiKey() != null && !s.getApiKey().isBlank(),
                        maskKey(s.getApiKey()),
                        s.getBaseUrl() != null ? s.getBaseUrl() : "",
                        s.getModel() != null ? s.getModel() : ""
                ))
                .orElseGet(() -> new LlmSettingsResponse(false, "", "", ""));
    }

    @PutMapping
    public ResponseEntity<SaveResponse> save(@RequestBody LlmSettingsRequest request) {
        LlmSettings settings = repo.findById(1L).orElseGet(LlmSettings::new);
        String[] before = {settings.getApiKey(), settings.getBaseUrl(), settings.getModel()};
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            settings.setApiKey(request.apiKey().trim());
        }
        if (request.baseUrl() != null) {
            settings.setBaseUrl(request.baseUrl().trim());
        }
        if (request.model() != null) {
            settings.setModel(request.model().trim());
        }
        repo.save(settings);

        boolean changed = !java.util.Arrays.equals(before,
                new String[]{settings.getApiKey(), settings.getBaseUrl(), settings.getModel()});
        if (!changed) {
            return ResponseEntity.ok(new SaveResponse("saved", "No change.", false));
        }
        restarter.restart();
        return ResponseEntity.ok(new SaveResponse("saved",
                "Applying the new settings: the application restarts and is back in a few seconds.", true));
    }

    private String maskKey(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 8) return "****";
        return "*".repeat(key.length() - 4) + key.substring(key.length() - 4);
    }

    public record LlmSettingsRequest(String apiKey, String baseUrl, String model) {}
    record LlmSettingsResponse(boolean apiKeySet, String apiKeyMasked, String baseUrl, String model) {}
    record SaveResponse(String status, String note, boolean restarting) {}
}