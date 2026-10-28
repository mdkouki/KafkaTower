package com.lynra.kafkatower.controller;

import com.lynra.kafkatower.agents.specialists.config.OpenSearchClientProvider;
import com.lynra.kafkatower.agents.specialists.config.OsProperties;
import com.lynra.kafkatower.config.AdminSettingsStore;
import com.lynra.kafkatower.config.ApplicationRestarter;
import com.lynra.kafkatower.model.OpenSearchSettings;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/settings/opensearch")
public class OpenSearchSettingsController {

    private final AdminSettingsStore store;
    private final ApplicationRestarter restarter;
    private final Environment env;

    public OpenSearchSettingsController(AdminSettingsStore store, ApplicationRestarter restarter, Environment env) {
        this.store = store;
        this.restarter = restarter;
        this.env = env;
    }

    private boolean isSaved() {
        return store.read(OpenSearchSettings.SECTION, OpenSearchSettings.class).isPresent();
    }

    /** What is in effect: the saved settings, else whatever yml/env configured. */
    private OpenSearchSettings current() {
        return store.read(OpenSearchSettings.SECTION, OpenSearchSettings.class).orElseGet(() -> {
            OsProperties yml = Binder.get(env).bind("agent.mcp.os", OsProperties.class).orElseGet(OsProperties::new);
            List<OpenSearchSettings.Cluster> clusters = new ArrayList<>();
            for (Map.Entry<String, OsProperties.ClusterOs> e : yml.getClusters().entrySet()) {
                OsProperties.ClusterOs c = e.getValue();
                clusters.add(new OpenSearchSettings.Cluster(e.getKey(), c.getUrl(), c.getUsername(),
                        c.getPassword(), c.getIndex()));
            }
            return new OpenSearchSettings(yml.isEnabled(), yml.getDefaultCluster(), clusters);
        });
    }

    @GetMapping
    public OpenSearchResponse get() {
        OpenSearchSettings os = current();
        return new OpenSearchResponse(os.enabled(), nz(os.defaultCluster()), os.clusters().stream()
                .map(c -> new ClusterView(c.name(), nz(c.url()), nz(c.username()),
                        c.password() != null && !c.password().isBlank(), nz(c.index())))
                .toList());
    }

    @PutMapping
    public ResponseEntity<SettingsSaveResponse> save(@RequestBody OpenSearchRequest request) {
        OpenSearchSettings before = current();
        Map<String, OpenSearchSettings.Cluster> stored = new HashMap<>();
        before.clusters().forEach(c -> stored.put(c.name(), c));

        List<OpenSearchSettings.Cluster> clusters = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (ClusterInput in : request.clusters() == null ? List.<ClusterInput>of() : request.clusters()) {
            String name = nz(in.name()).trim();
            String url = nz(in.url()).trim();
            if (name.isEmpty() || url.isEmpty()) {
                throw invalid("Every cluster needs a name and a URL.");
            }
            if (!names.add(name)) {
                throw invalid("Cluster name '" + name + "' is used twice.");
            }
            String lookup = nz(in.previousName()).trim().isEmpty() ? name : in.previousName().trim();
            OpenSearchSettings.Cluster old = stored.get(lookup);
            String password = in.password() != null && !in.password().isBlank() ? in.password()
                    : Boolean.TRUE.equals(in.clearPassword()) || old == null ? "" : nz(old.password());
            clusters.add(new OpenSearchSettings.Cluster(name, url, nz(in.username()).trim(), password,
                    nz(in.index()).trim()));
        }
        String defaultCluster = nz(request.defaultCluster()).trim();
        if (!defaultCluster.isEmpty() && !names.contains(defaultCluster)) {
            throw invalid("The default cluster '" + defaultCluster + "' is not in the list.");
        }
        OpenSearchSettings after = new OpenSearchSettings(Boolean.TRUE.equals(request.enabled()), defaultCluster, clusters);

        if (after.enabled()) {
            for (OpenSearchSettings.Cluster c : clusters) {
                String error = probe(c);
                if (error != null) {
                    throw invalid("Cluster '" + c.name() + "' (" + c.url() + "): " + error);
                }
            }
        }
        if (after.equals(before) && isSaved()) {
            return ResponseEntity.ok(new SettingsSaveResponse("saved", "Verified. No change.", false));
        }
        store.write(OpenSearchSettings.SECTION, after);
        restarter.restart();
        return ResponseEntity.ok(new SettingsSaveResponse("saved",
                "Verified and saved. The application restarts and is back in a few seconds.", true));
    }

    /** Null when the cluster answers and its index (if any) is searchable; else the reason. */
    String probe(OpenSearchSettings.Cluster c) {
        try {
            OpenSearchClientProvider client = new OpenSearchClientProvider(
                    c.name(), c.url(), c.username(), c.password(), c.index());
            client.get("/_cluster/health");
            if (c.index() != null && !c.index().isBlank()) {
                client.get("/" + c.index() + "/_count");
            }
            return null;
        } catch (Exception e) {
            String message = e.getMessage();
            return message == null || message.isBlank() ? "connection failed (" + e.getClass().getSimpleName() + ")" : message;
        }
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    private static String nz(String v) { return v == null ? "" : v; }

    public record ClusterInput(String name, String previousName, String url, String username, String password,
                               Boolean clearPassword, String index) {}
    public record OpenSearchRequest(Boolean enabled, String defaultCluster, List<ClusterInput> clusters) {}
    record ClusterView(String name, String url, String username, boolean passwordSet, String index) {}
    record OpenSearchResponse(boolean enabled, String defaultCluster, List<ClusterView> clusters) {}
}
