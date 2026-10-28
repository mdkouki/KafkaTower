package com.lynra.kafkatower.controller;

import com.lynra.kafkatower.agents.specialists.config.PromQlQueryTools;
import com.lynra.kafkatower.config.AdminSettingsStore;
import com.lynra.kafkatower.config.ApplicationRestarter;
import com.lynra.kafkatower.model.MetricsSettings;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/settings/metrics")
public class MetricsSettingsController {

    private final AdminSettingsStore store;
    private final ApplicationRestarter restarter;
    private final Environment env;

    public MetricsSettingsController(AdminSettingsStore store, ApplicationRestarter restarter, Environment env) {
        this.store = store;
        this.restarter = restarter;
        this.env = env;
    }

    /** What is in effect: the saved settings, else whatever yml/env configured. */
    private MetricsSettings current() {
        return store.read(MetricsSettings.SECTION, MetricsSettings.class).orElseGet(() -> new MetricsSettings(
                env.getProperty("agent.metrics.url", ""), env.getProperty("agent.metrics.token", ""),
                env.getProperty("agent.metrics.username", ""), env.getProperty("agent.metrics.password", ""),
                env.getProperty("agent.metrics.tenant", "")));
    }

    @GetMapping
    public MetricsResponse get() {
        MetricsSettings m = current();
        return new MetricsResponse(nz(m.url()), set(m.token()), nz(m.username()), set(m.password()), nz(m.tenant()));
    }

    @PutMapping
    public ResponseEntity<SettingsSaveResponse> save(@RequestBody MetricsRequest request) {
        MetricsSettings before = current();
        String url = nz(request.url()).trim();
        if (url.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "The metrics URL is required.");
        }
        MetricsSettings after = new MetricsSettings(url,
                secret(request.token(), Boolean.TRUE.equals(request.clearToken()), before.token()),
                nz(request.username()).trim(),
                secret(request.password(), Boolean.TRUE.equals(request.clearPassword()), before.password()),
                nz(request.tenant()).trim());

        String error = probe(after);
        if (error != null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Cannot query the metrics backend at " + url + ": " + error);
        }
        if (after.equals(before) && store.read(MetricsSettings.SECTION, MetricsSettings.class).isPresent()) {
            return ResponseEntity.ok(new SettingsSaveResponse("saved", "Verified. No change.", false));
        }
        store.write(MetricsSettings.SECTION, after);
        restarter.restart();
        return ResponseEntity.ok(new SettingsSaveResponse("saved",
                "Verified and saved. The application restarts and is back in a few seconds.", true));
    }

    /** Overridable in tests. */
    String probe(MetricsSettings m) {
        return new PromQlQueryTools(m.url(), m.token(), m.username(), m.password(), m.tenant()).probe();
    }

    /** Blank keeps the stored secret; the clear flag removes it. */
    private static String secret(String submitted, boolean clear, String stored) {
        if (clear) return "";
        return submitted != null && !submitted.isBlank() ? submitted.trim() : nz(stored);
    }

    private static String nz(String v) { return v == null ? "" : v; }
    private static boolean set(String v) { return v != null && !v.isBlank(); }

    public record MetricsRequest(String url, String token, Boolean clearToken, String username,
                                 String password, Boolean clearPassword, String tenant) {}
    record MetricsResponse(String url, boolean tokenSet, String username, boolean passwordSet, String tenant) {}
}
