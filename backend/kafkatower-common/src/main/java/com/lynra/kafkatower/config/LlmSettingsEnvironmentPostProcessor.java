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
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Decides, before the application context starts, whether the AI assistant runs.
 * <p>
 * The LLM settings an admin saves in the UI live in the {@code llm_settings} table. When they are
 * complete (API key, base URL and model) this injects them as the Spring properties Embabel and
 * Spring AI read, and sets {@link #ASSISTANT_ENABLED} so the beans marked
 * {@link ConditionalOnAssistantEnabled} are created. When they are not, it sets the flag to
 * {@code false} and excludes the autoconfigurations that need an LLM (Embabel and Spring AI's
 * OpenAI), so the rest of the console still starts without any key.
 * <p>
 * Runs again on every context restart (see {@link ApplicationRestarter}), which is how saving
 * new settings takes effect without restarting the process. If {@link #ASSISTANT_ENABLED} is
 * already set (environment variable, yml or test property), that wins and the database is not
 * consulted — configuration as code.
 */
public class LlmSettingsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    public static final String ASSISTANT_ENABLED = "kafkatower.assistant.enabled";

    /** Autoconfigurations that cannot start without an LLM key; excluded while unconfigured. */
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

    /** The three values the assistant needs; any blank one means "not configured". */
    record LlmSettings(String apiKey, String baseUrl, String model) {
        boolean complete() {
            return StringUtils.hasText(apiKey) && StringUtils.hasText(baseUrl) && StringUtils.hasText(model);
        }
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> props = new HashMap<>();
        String explicit = environment.getProperty(ASSISTANT_ENABLED);
        boolean enabled;
        if (explicit != null) {
            // Configured as code (env var, yml, test property, or the restarter's safe-mode retry).
            enabled = Boolean.parseBoolean(explicit);
        } else {
            LlmSettings settings = readSettings(environment);
            enabled = settings.complete();
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
        }
        if (!enabled) {
            props.put("spring.autoconfigure.exclude", String.join(",", excludes(environment)));
            log.info("AI assistant disabled: no usable LLM settings (an admin can set them in the UI).");
        }
        addKafka(environment, props);
        addOpenSearch(environment, props);
        addMetrics(environment, props);
        environment.getPropertySources().addFirst(new MapPropertySource("llmSettingsDb", props));
    }

    /** The Kafka clusters configured in the admin console become {@code kafka.clusters.*}. */
    private static void addKafka(ConfigurableEnvironment environment, Map<String, Object> props) {
        String json = readSection(environment, KafkaSettings.SECTION);
        if (json == null) return;
        for (KafkaSettings.Cluster c : parseOrNull(json, KafkaSettings.SECTION, KafkaSettings.class) == null ? List.<KafkaSettings.Cluster>of() : parseOrNull(json, KafkaSettings.SECTION, KafkaSettings.class).clusters()) {
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

    /** An unreadable saved section must not stop the application from starting: warn and ignore it. */
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

    /** OpenSearch connections saved in the admin console override {@code agent.mcp.os.*} from yml. */
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

    /** The metrics backend saved in the admin console overrides {@code agent.metrics.*} from yml. */
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

    /** Existing {@code spring.autoconfigure.exclude} entries plus the LLM ones, without duplicates. */
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

    /** The JSON payload of an admin settings section, or null when it was never saved. */
    private static String readSection(ConfigurableEnvironment environment, String section) {
        try (Connection conn = connect(environment)) {
            if (conn == null) return null;
            try (java.sql.PreparedStatement ps = conn.prepareStatement(
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

    /** Any JDBC driver on the classpath works (PostgreSQL, H2); null when no datasource is configured. */
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

    /** jdbc:h2:file:./db  →  jdbc:h2:file:./db;IFEXISTS=TRUE. Other URLs are returned unchanged. */
    static String ifExists(String url) {
        if (!url.toLowerCase().startsWith("jdbc:h2:file:")) {
            return url;
        }
        return url.replaceAll("(?i);?IFEXISTS=[^;]*", "") + ";IFEXISTS=TRUE";
    }
}
