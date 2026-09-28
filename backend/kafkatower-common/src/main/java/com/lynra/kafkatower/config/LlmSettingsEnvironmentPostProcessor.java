package com.lynra.kafkatower.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Reads LLM settings persisted in H2 by admins and injects them as Spring properties
 * before the application context starts — so Embabel and Spring AI pick them up.
 * Falls back silently to application.yml values if no DB row exists yet.
 */
public class LlmSettingsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Logger log = Logger.getLogger(LlmSettingsEnvironmentPostProcessor.class.getName());

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String jdbcUrl = environment.getProperty("spring.datasource.url", "jdbc:h2:file:./kafkatower-db");
        // Strip any extra H2 options from the configured URL, then add IFEXISTS=TRUE
        // so we don't create the DB if it doesn't exist yet.
        String fileUrl = toIfExistsUrl(jdbcUrl);

        Map<String, Object> props = new HashMap<>();
        try {
            Class.forName("org.h2.Driver");
            try (Connection conn = DriverManager.getConnection(fileUrl, "sa", "")) {
                try (ResultSet rs = conn.createStatement()
                        .executeQuery("SELECT api_key, base_url, model FROM llm_settings WHERE id = 1")) {
                    if (rs.next()) {
                        String apiKey = rs.getString("api_key");
                        String baseUrl = rs.getString("base_url");
                        String model = rs.getString("model");

                        if (apiKey != null && !apiKey.isBlank()) {
                            props.put("spring.ai.openai.api-key", apiKey);
                            props.put("embabel.agent.platform.models.openai.api-key", apiKey);
                        }
                        if (baseUrl != null && !baseUrl.isBlank()) {
                            props.put("spring.ai.openai.base-url", baseUrl);
                            props.put("embabel.agent.platform.models.openai.base-url", baseUrl);
                        }
                        if (model != null && !model.isBlank()) {
                            props.put("spring.ai.openai.chat.options.model", model);
                            props.put("embabel.models.default-llm", model);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // DB doesn't exist yet or table missing — normal on first run, fall back to application.yml
            log.fine("LlmSettings not loaded from DB (first run or table absent): " + e.getMessage());
        }

        if (!props.isEmpty()) {
            environment.getPropertySources().addFirst(
                    new MapPropertySource("llmSettingsDb", props));
            log.info("LLM settings loaded from database (api-key, base-url, and/or model overridden).");
        }
    }

    private String toIfExistsUrl(String url) {
        // jdbc:h2:file:./kafkatower-db  →  jdbc:h2:file:./kafkatower-db;IFEXISTS=TRUE
        // Remove existing IFEXISTS option if present, then re-add it.
        String base = url.replaceAll("(?i);?IFEXISTS=[^;]*", "");
        return base + ";IFEXISTS=TRUE";
    }
}