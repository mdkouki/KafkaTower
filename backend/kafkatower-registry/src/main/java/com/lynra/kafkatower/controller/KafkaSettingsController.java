package com.lynra.kafkatower.controller;

import com.lynra.kafkatower.config.AdminSettingsStore;
import com.lynra.kafkatower.config.ApplicationRestarter;
import com.lynra.kafkatower.content.TopicSchemaRepository;
import com.lynra.kafkatower.kafka.KafkaClusterVerifier;
import com.lynra.kafkatower.model.KafkaSettings;
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
import java.util.stream.Collectors;

/**
 * The Kafka clusters of the console. Every cluster is verified against its brokers before the
 * list is saved; clusters the admin removed lose their stored data (uploaded topic schemas),
 * while a renamed cluster leaves the old name's data behind.
 */
@RestController
@RequestMapping("/api/admin/settings/kafka")
public class KafkaSettingsController {

    private static final Set<String> SECURITY = Set.of(KafkaSettings.NONE, KafkaSettings.MTLS, KafkaSettings.SASL);
    private static final String DEFAULT_SASL_MECHANISM = "PLAIN";
    private static final Set<String> MECHANISMS = Set.of(DEFAULT_SASL_MECHANISM, "SCRAM-SHA-256", "SCRAM-SHA-512");
    private static final String DEFAULT_STORE_TYPE = "PKCS12";

    private final AdminSettingsStore store;
    private final ApplicationRestarter restarter;
    private final KafkaClusterVerifier verifier;
    private final TopicSchemaRepository schemas;

    public KafkaSettingsController(AdminSettingsStore store, ApplicationRestarter restarter,
                                   KafkaClusterVerifier verifier, TopicSchemaRepository schemas) {
        this.store = store;
        this.restarter = restarter;
        this.verifier = verifier;
        this.schemas = schemas;
    }

    private KafkaSettings current() {
        return store.read(KafkaSettings.SECTION, KafkaSettings.class).orElseGet(() -> new KafkaSettings(List.of()));
    }

    @GetMapping
    public KafkaResponse get() {
        return new KafkaResponse(current().clusters().stream().map(KafkaSettingsController::view).toList());
    }

    @PutMapping
    public ResponseEntity<SettingsSaveResponse> save(@RequestBody KafkaRequest request) {
        KafkaSettings before = current();
        List<KafkaSettings.Cluster> clusters = buildClusters(request, before);
        Set<String> names = clusters.stream().map(KafkaSettings.Cluster::name).collect(Collectors.toSet());
        KafkaSettings after = new KafkaSettings(clusters);

        boolean unchanged = after.equals(before) && store.read(KafkaSettings.SECTION, KafkaSettings.class).isPresent();
        int brokers = verifyAll(clusters);
        List<String> removed = removedNames(request.removedClusters(), names);
        if (unchanged && removed.isEmpty()) {
            return ResponseEntity.ok(new SettingsSaveResponse("saved", "Verified. No change.", false));
        }
        store.write(KafkaSettings.SECTION, after);
        removed.forEach(schemas::deleteByClusterName);
        restarter.restart();
        return ResponseEntity.ok(new SettingsSaveResponse("saved", savedNote(clusters.size(), brokers, removed), true));
    }

    private List<KafkaSettings.Cluster> buildClusters(KafkaRequest request, KafkaSettings before) {
        Map<String, KafkaSettings.Cluster> stored = new HashMap<>();
        before.clusters().forEach(c -> stored.put(c.name(), c));

        List<KafkaSettings.Cluster> clusters = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (ClusterInput in : request.clusters() == null ? List.<ClusterInput>of() : request.clusters()) {
            KafkaSettings.Cluster cluster = build(in, stored.get(storedNameOf(in)));
            if (!names.add(cluster.name())) {
                throw invalid("Cluster name '" + cluster.name() + "' is used twice.");
            }
            clusters.add(cluster);
        }
        return clusters;
    }

    private static String storedNameOf(ClusterInput in) {
        String previousName = nz(in.previousName()).trim();
        return previousName.isEmpty() ? nz(in.name()).trim() : previousName;
    }

    private int verifyAll(List<KafkaSettings.Cluster> clusters) {
        int brokers = 0;
        for (KafkaSettings.Cluster c : clusters) {
            try {
                brokers += verifier.verify(c);
            } catch (IllegalArgumentException e) {
                throw invalid("Cluster '" + c.name() + "' (" + c.bootstrapServers() + "): " + e.getMessage());
            }
        }
        return brokers;
    }

    private static String savedNote(int clusterCount, int brokers, List<String> removed) {
        return "Verified " + clusterCount + " cluster(s), " + brokers + " broker(s) answered. "
                + (removed.isEmpty() ? "" : "Removed the data of: " + String.join(", ", removed) + ". ")
                + "The application restarts and is back in a few seconds.";
    }

    /** Names the admin deleted (and did not re-add) whose data should go; renamed clusters are not in this list. */
    private static List<String> removedNames(List<String> requested, Set<String> keptNames) {
        List<String> removed = new ArrayList<>();
        for (String name : requested == null ? List.<String>of() : requested) {
            String trimmed = nz(name).trim();
            if (!trimmed.isEmpty() && !keptNames.contains(trimmed) && !removed.contains(trimmed)) {
                removed.add(trimmed);
            }
        }
        return removed;
    }

    private static KafkaSettings.Cluster build(ClusterInput in, KafkaSettings.Cluster old) {
        String name = nz(in.name()).trim();
        String bootstrap = nz(in.bootstrapServers()).trim();
        if (name.isEmpty() || bootstrap.isEmpty()) {
            throw invalid("Every cluster needs a name and bootstrap servers.");
        }
        String security = nz(in.security()).trim().toUpperCase();
        if (!SECURITY.contains(security)) {
            throw invalid("Cluster '" + name + "': security must be NONE, MTLS or SASL.");
        }
        KafkaSettings.Sasl sasl = KafkaSettings.SASL.equals(security)
                ? buildSasl(name, in.sasl(), old != null ? old.sasl() : null)
                : null;
        boolean tls = KafkaSettings.MTLS.equals(security) || (sasl != null && sasl.tls());
        KafkaSettings.Ssl ssl = tls
                ? buildSsl(KafkaSettings.MTLS.equals(security), in.ssl(), old != null ? old.ssl() : null)
                : null;
        return new KafkaSettings.Cluster(name, bootstrap, security, ssl, sasl);
    }

    private static KafkaSettings.Sasl buildSasl(String clusterName, SaslInput input, KafkaSettings.Sasl old) {
        SaslInput s = input == null ? new SaslInput(null, null, null, false) : input;
        String mechanism = nz(s.mechanism()).trim().isEmpty() ? DEFAULT_SASL_MECHANISM : s.mechanism().trim().toUpperCase();
        if (!MECHANISMS.contains(mechanism)) {
            throw invalid("Cluster '" + clusterName + "': SASL mechanism must be PLAIN, SCRAM-SHA-256 or SCRAM-SHA-512.");
        }
        String username = nz(s.username()).trim();
        String password = keep(s.password(), old != null ? old.password() : null);
        if (username.isEmpty() || password.isEmpty()) {
            throw invalid("Cluster '" + clusterName + "': SASL needs a username and a password.");
        }
        return new KafkaSettings.Sasl(mechanism, username, password, Boolean.TRUE.equals(s.tls()));
    }

    private static KafkaSettings.Ssl buildSsl(boolean mtls, SslInput input, KafkaSettings.Ssl old) {
        SslInput s = input == null ? new SslInput(null, null, null, null, null, null, null) : input;
        return new KafkaSettings.Ssl(
                mtls ? nz(s.keystoreLocation()).trim() : "",
                mtls ? keep(s.keystorePassword(), old != null ? old.keystorePassword() : null) : "",
                typeOrDefault(s.keystoreType()),
                mtls ? keep(s.keyPassword(), old != null ? old.keyPassword() : null) : "",
                nz(s.truststoreLocation()).trim(),
                keep(s.truststorePassword(), old != null ? old.truststorePassword() : null),
                typeOrDefault(s.truststoreType()));
    }

    private static ClusterView view(KafkaSettings.Cluster c) {
        KafkaSettings.Ssl ssl = c.ssl();
        KafkaSettings.Sasl sasl = c.sasl();
        return new ClusterView(c.name(), c.bootstrapServers(), c.security(),
                new SslView(ssl == null ? "" : nz(ssl.keystoreLocation()), ssl == null ? DEFAULT_STORE_TYPE : typeOrDefault(ssl.keystoreType()),
                        ssl != null && set(ssl.keystorePassword()), ssl != null && set(ssl.keyPassword()),
                        ssl == null ? "" : nz(ssl.truststoreLocation()), ssl == null ? DEFAULT_STORE_TYPE : typeOrDefault(ssl.truststoreType()),
                        ssl != null && set(ssl.truststorePassword())),
                new SaslView(sasl == null ? DEFAULT_SASL_MECHANISM : nz(sasl.mechanism()), sasl == null ? "" : nz(sasl.username()),
                        sasl != null && set(sasl.password()), sasl != null && sasl.tls()));
    }

    /** A blank submitted secret keeps the stored one. */
    private static String keep(String submitted, String stored) {
        return submitted != null && !submitted.isBlank() ? submitted.trim() : nz(stored);
    }

    private static String typeOrDefault(String type) {
        return type == null || type.isBlank() ? DEFAULT_STORE_TYPE : type.trim().toUpperCase();
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    private static String nz(String v) { return v == null ? "" : v; }
    private static boolean set(String v) { return v != null && !v.isBlank(); }

    public record SslInput(String keystoreLocation, String keystorePassword, String keystoreType, String keyPassword,
                           String truststoreLocation, String truststorePassword, String truststoreType) {}
    public record SaslInput(String mechanism, String username, String password, Boolean tls) {}
    /** {@code previousName} is the name the row had when loaded, so a renamed cluster keeps its stored secrets. */
    public record ClusterInput(String name, String previousName, String bootstrapServers, String security,
                               SslInput ssl, SaslInput sasl) {}
    public record KafkaRequest(List<ClusterInput> clusters, List<String> removedClusters) {}

    record SslView(String keystoreLocation, String keystoreType, boolean keystorePasswordSet, boolean keyPasswordSet,
                   String truststoreLocation, String truststoreType, boolean truststorePasswordSet) {}
    record SaslView(String mechanism, String username, boolean passwordSet, boolean tls) {}
    record ClusterView(String name, String bootstrapServers, String security, SslView ssl, SaslView sasl) {}
    record KafkaResponse(List<ClusterView> clusters) {}
}
