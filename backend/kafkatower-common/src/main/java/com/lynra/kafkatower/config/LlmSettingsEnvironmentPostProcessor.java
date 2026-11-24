package com.lynra.kafkatower.config;

import com.lynra.kafkatower.model.KafkaSettings;
import com.lynra.kafkatower.model.MetricsSettings;
import com.lynra.kafkatower.model.OpenSearchSettings;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/*
 * Runs again on every context restart (see ApplicationRestarter), which is how saved settings take
 * effect. An explicit ASSISTANT_ENABLED (env var, yml, test property) wins and the database is not
 * consulted. When LLM settings are incomplete, the LLM autoconfigurations are excluded so the rest
 * of the console still starts without a key.
 */
public class LlmSettingsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    public static final String ASSISTANT_ENABLED = "kafkatower.assistant.enabled";

    static final List<String> LLM_AUTOCONFIGURATIONS = List.of(
            "com.embabel.agent.autoconfigure.platform.AgentPlatformAutoConfiguration",
            "com.embabel.agent.autoconfigure.models.openai.AgentOpenAiAutoConfiguration",
            "com.embabel.agent.autoconfigure.observability.OpenTelemetrySdkAutoConfiguration",
            "com.embabel.agent.autoconfigure.observability.MicrometerTracingAutoConfiguration",
            "com.embabel.agent.autoconfigure.observability.ObservabilityAutoConfiguration",
            "com.embabel.agent.autoconfigure.observability.TrackedAspectAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration");

    private static final Logger log = Logger.getLogger(LlmSettingsEnvironmentPostProcessor.class.getName());

    record LlmSettings(String apiKey, String baseUrl, String model) {
        boolean complete() {
            return StringUtils.hasText(apiKey) && StringUtils.hasText(baseUrl) && StringUtils.hasText(model);
        }
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> props = new HashMap<>();
        String explicit = environment.getProperty(ASSISTANT_ENABLED);
        boolean enabled = explicit != null ? Boolean.parseBoolean(explicit) : applyDatabaseLlmSettings(environment, props);
        if (!enabled) {
            props.put("spring.autoconfigure.exclude", String.join(",", excludes(environment)));
            log.info("AI assistant disabled: no usable LLM settings (an admin can set them in the UI).");
        }
        addKafka(environment, props);
        addOpenSearch(environment, props);
        addMetrics(environment, props);
        environment.getPropertySources().addFirst(new MapPropertySource("llmSettingsDb", props));
    }

    private static boolean applyDatabaseLlmSettings(ConfigurableEnvironment environment, Map<String, Object> props) {
        LlmSettings settings = readSettings(environment);
        boolean enabled = settings.complete();
        props.put(ASSISTANT_ENABLED, String.valueOf(enabled));
        if (enabled) {
            props.put("spring.ai.openai.api-key", settings.apiKey());
            props.put("spring.ai.openai.base-url", settings.baseUrl());
            props.put("spring.ai.openai.chat.options.model", settings.model());
            props.put("embabel.agent.platform.models.openai.api-key", settings.apiKey());
            props.put("embabel.agent.platform.models.openai.base-url", settings.baseUrl());
            props.put("embabel.models.default-llm", settings.model());
            log.info("AI assistant enabled: LLM settings loaded from the database.");
        }
        return enabled;
    }

    private static void addKafka(ConfigurableEnvironment environment, Map<String, Object> props) {
        String json = readSection(environment, KafkaSettings.SECTION);
        if (json == null) return;
        KafkaSettings kafka = parseOrNull(json, KafkaSettings.SECTION, KafkaSettings.class);
        if (kafka == null) return;
        for (KafkaSettings.Cluster c : kafka.clusters()) {
            String prefix = "kafka.clusters[" + c.name() + "].";
            props.put(prefix + "bootstrap-servers", c.bootstrapServers());
            props.put(prefix + "security-protocol", c.securityProtocol());
            if (c.usesTls() && c.ssl() != null) {
                KafkaSettings.Ssl ssl = c.ssl();
                putIfText(props, prefix + "ssl.keystore-location", ssl.keystoreLocation());
                putIfText(props, prefix + "ssl.keystore-password", ssl.keystorePassword());
                putIfText(props, prefix + "ssl.keystore-type", ssl.keystoreType());
                putIfText(props, prefix + "ssl.key-password", ssl.keyPassword());
                putIfText(props, prefix + "ssl.truststore-location", ssl.truststoreLocation());
                putIfText(props, prefix + "ssl.truststore-password", ssl.truststorePassword());
                putIfText(props, prefix + "ssl.truststore-type", ssl.truststoreType());
            }
            if (KafkaSettings.SASL.equals(c.security()) && c.sasl() != null) {
                putIfText(props, prefix + "sasl.mechanism", c.sasl().mechanism());
                putIfText(props, prefix + "sasl.username", c.sasl().username());
                putIfText(props, prefix + "sasl.password", c.sasl().password());
            }
        }
    }

    /** An unreadable saved section must not stop the application from starting. */
    private static <T> T parseOrNull(String json, String section, Class<T> type) {
        try {
            return AdminSettingsStore.parse(json, type);
        } catch (IllegalStateException e) {
            log.warning("Ignoring unreadable admin settings '" + section + "' (save them again in the console): "
                    + e.getMessage());
            return null;
        }
    }

    private static void putIfText(Map<String, Object> props, String key, String value) {
        if (StringUtils.hasText(value)) props.put(key, value);
    }

    private static void addOpenSearch(ConfigurableEnvironment environment, Map<String, Object> props) {
        String json = readSection(environment, OpenSearchSettings.SECTION);
        if (json == null) return;
        OpenSearchSettings os = parseOrNull(json, OpenSearchSettings.SECTION, OpenSearchSettings.class);
        if (os == null) return;
        props.put("agent.mcp.os.enabled", String.valueOf(os.enabled()));
        props.put("agent.mcp.os.default-cluster", os.defaultCluster() == null ? "" : os.defaultCluster());
        for (OpenSearchSettings.Cluster c : os.clusters()) {
            String prefix = "agent.mcp.os.clusters[" + c.name() + "].";
            props.put(prefix + "url", c.url());
            props.put(prefix + "username", c.username() == null ? "" : c.username());
            props.put(prefix + "password", c.password() == null ? "" : c.password());
            props.put(prefix + "index", c.index() == null ? "" : c.index());
        }
    }

    private static void addMetrics(ConfigurableEnvironment environment, Map<String, Object> props) {
        String json = readSection(environment, MetricsSettings.SECTION);
        if (json == null) return;
        MetricsSettings m = parseOrNull(json, MetricsSettings.SECTION, MetricsSettings.class);
        if (m == null) return;
        props.put("agent.metrics.url", m.url() == null ? "" : m.url());
        props.put("agent.metrics.token", m.token() == null ? "" : m.token());
        props.put("agent.metrics.username", m.username() == null ? "" : m.username());
        props.put("agent.metrics.password", m.password() == null ? "" : m.password());
        props.put("agent.metrics.tenant", m.tenant() == null ? "" : m.tenant());
    }

    private static List<String> excludes(ConfigurableEnvironment environment) {
        List<String> excludes = new ArrayList<>();
        String existing = environment.getProperty("spring.autoconfigure.exclude", "");
        for (String name : existing.split(",")) {
            if (StringUtils.hasText(name)) excludes.add(name.trim());
        }
        for (String name : LLM_AUTOCONFIGURATIONS) {
            if (!excludes.contains(name)) excludes.add(name);
        }
        return excludes;
    }

    private static LlmSettings readSettings(ConfigurableEnvironment environment) {
        try (Connection conn = connect(environment)) {
            if (conn == null) return new LlmSettings(null, null, null);
            try (ResultSet rs = conn.createStatement()
                    .executeQuery("SELECT api_key, base_url, model FROM llm_settings WHERE id = 1")) {
                if (rs.next()) {
                    return new LlmSettings(rs.getString("api_key"), rs.getString("base_url"), rs.getString("model"));
                }
            }
        } catch (Exception e) {
            // Database or table not there yet (first start, before Flyway has run): not configured.
            log.fine("LLM settings not loaded from the database: " + e.getMessage());
        }
        return new LlmSettings(null, null, null);
    }

    private static String readSection(ConfigurableEnvironment environment, String section) {
        try (Connection conn = connect(environment)) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT payload FROM admin_settings WHERE section = ?")) {
                ps.setString(1, section);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString("payload") : null;
                }
            }
        } catch (Exception e) {
            log.fine("Admin settings '" + section + "' not loaded from the database: " + e.getMessage());
            return null;
        }
    }

    private static Connection connect(ConfigurableEnvironment environment) throws Exception {
        String url = environment.getProperty("spring.datasource.url");
        if (!StringUtils.hasText(url)) {
            return null;
        }
        Properties credentials = new Properties();
        String user = environment.getProperty("spring.datasource.username");
        if (StringUtils.hasText(user)) credentials.setProperty("user", user);
        String password = environment.getProperty("spring.datasource.password");
        if (password != null) credentials.setProperty("password", password);
        // For a file H2 database, don't create it just to look: IFEXISTS makes a missing one fail.
        return DriverManager.getConnection(ifExists(url), credentials);
    }

    static String ifExists(String url) {
        if (!url.toLowerCase().startsWith("jdbc:h2:file:")) {
            return url;
        }
        return url.replaceAll("(?i);?IFEXISTS=[^;]*", "") + ";IFEXISTS=TRUE";
    }
}
