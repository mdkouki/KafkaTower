package com.lynra.kafkatower.config;

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
        environment.getPropertySources().addFirst(new MapPropertySource("llmSettingsDb", props));
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
        String url = environment.getProperty("spring.datasource.url");
        if (!StringUtils.hasText(url)) {
            return new LlmSettings(null, null, null);
        }
        Properties credentials = new Properties();
        String user = environment.getProperty("spring.datasource.username");
        if (StringUtils.hasText(user)) credentials.setProperty("user", user);
        String password = environment.getProperty("spring.datasource.password");
        if (password != null) credentials.setProperty("password", password);

        // Any JDBC driver on the classpath works (PostgreSQL, H2). For a file H2 database, don't
        // create it just to look: IFEXISTS makes a missing database fail instead.
        try (Connection conn = DriverManager.getConnection(ifExists(url), credentials);
             ResultSet rs = conn.createStatement()
                     .executeQuery("SELECT api_key, base_url, model FROM llm_settings WHERE id = 1")) {
            if (rs.next()) {
                return new LlmSettings(rs.getString("api_key"), rs.getString("base_url"), rs.getString("model"));
            }
        } catch (Exception e) {
            // Database or table not there yet (first start, before Flyway has run): not configured.
            log.fine("LLM settings not loaded from the database: " + e.getMessage());
        }
        return new LlmSettings(null, null, null);
    }

    /** jdbc:h2:file:./db  →  jdbc:h2:file:./db;IFEXISTS=TRUE. Other URLs are returned unchanged. */
    static String ifExists(String url) {
        if (!url.toLowerCase().startsWith("jdbc:h2:file:")) {
            return url;
        }
        return url.replaceAll("(?i);?IFEXISTS=[^;]*", "") + ";IFEXISTS=TRUE";
    }
}
