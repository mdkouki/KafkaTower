package com.lynra.kafkatower.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LlmSettingsEnvironmentPostProcessorTest {

    private final LlmSettingsEnvironmentPostProcessor processor = new LlmSettingsEnvironmentPostProcessor();

    private static String newDatabase() throws Exception {
        String url = "jdbc:h2:mem:llm" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE llm_settings (id BIGINT PRIMARY KEY, api_key VARCHAR(1000), "
                    + "base_url VARCHAR(255), model VARCHAR(255))");
        }
        return url;
    }

    private static void save(String url, String key, String baseUrl, String model) throws Exception {
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("DELETE FROM llm_settings");
            s.execute("INSERT INTO llm_settings VALUES (1, " + quote(key) + ", " + quote(baseUrl) + ", " + quote(model) + ")");
        }
    }

    private static String quote(String v) {
        return v == null ? "NULL" : "'" + v + "'";
    }

    private MockEnvironment run(String url) {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.url", url)
                .withProperty("spring.datasource.username", "sa")
                .withProperty("spring.datasource.password", "")
                .withProperty("spring.autoconfigure.exclude", "org.example.Existing");
        processor.postProcessEnvironment(env, new SpringApplication());
        return env;
    }

    @Test
    void completeSettingsEnableTheAssistantAndFeedBothClients() throws Exception {
        String url = newDatabase();
        save(url, "sk-123", "http://llm.example/v1", "gpt-4o");

        MockEnvironment env = run(url);

        assertThat(env.getProperty("kafkatower.assistant.enabled")).isEqualTo("true");
        assertThat(env.getProperty("embabel.agent.platform.models.openai.api-key")).isEqualTo("sk-123");
        assertThat(env.getProperty("spring.ai.openai.api-key")).isEqualTo("sk-123");
        assertThat(env.getProperty("embabel.agent.platform.models.openai.base-url")).isEqualTo("http://llm.example/v1");
        assertThat(env.getProperty("spring.ai.openai.base-url")).isEqualTo("http://llm.example/v1");
        assertThat(env.getProperty("embabel.models.default-llm")).isEqualTo("gpt-4o");
        assertThat(env.getProperty("spring.ai.openai.chat.options.model")).isEqualTo("gpt-4o");
        assertThat(env.getProperty("spring.autoconfigure.exclude")).isEqualTo("org.example.Existing");
    }

    @Test
    void incompleteSettingsDisableTheAssistantAndExcludeTheLlmAutoconfigurations() throws Exception {
        String url = newDatabase();
        save(url, "sk-123", "http://llm.example/v1", "");

        MockEnvironment env = run(url);

        assertThat(env.getProperty("kafkatower.assistant.enabled")).isEqualTo("false");
        assertThat(env.getProperty("spring.autoconfigure.exclude").split(","))
                .contains("org.example.Existing")
                .containsAll(LlmSettingsEnvironmentPostProcessor.LLM_AUTOCONFIGURATIONS);
        assertThat(env.getProperty("spring.ai.openai.api-key")).isNull();
    }

    @Test
    void missingTableOrDatabaseMeansNotConfigured() {
        MockEnvironment env = run("jdbc:h2:mem:empty" + UUID.randomUUID().toString().replace("-", ""));

        assertThat(env.getProperty("kafkatower.assistant.enabled")).isEqualTo("false");
    }

    @Test
    void anExplicitFalseFlagExcludesTheLlmAutoconfigurationsWithoutReadingTheDatabase() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("kafkatower.assistant.enabled", "false")
                .withProperty("spring.datasource.url", "jdbc:h2:mem:never");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("kafkatower.assistant.enabled")).isEqualTo("false");
        assertThat(env.getProperty("spring.autoconfigure.exclude").split(","))
                .containsAll(LlmSettingsEnvironmentPostProcessor.LLM_AUTOCONFIGURATIONS);
    }

    @Test
    void anExplicitTrueFlagWinsAndTheDatabaseIsNotConsulted() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("kafkatower.assistant.enabled", "true")
                .withProperty("spring.datasource.url", "jdbc:h2:mem:never");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("kafkatower.assistant.enabled")).isEqualTo("true");
        assertThat(env.getProperty("spring.autoconfigure.exclude")).isNull();
        assertThat(env.getProperty("spring.ai.openai.api-key")).isNull();
    }

    @Test
    void onlyFileH2UrlsGetIfExists() {
        assertThat(LlmSettingsEnvironmentPostProcessor.ifExists("jdbc:h2:file:./db")).isEqualTo("jdbc:h2:file:./db;IFEXISTS=TRUE");
        assertThat(LlmSettingsEnvironmentPostProcessor.ifExists("jdbc:h2:file:./db;IFEXISTS=FALSE")).isEqualTo("jdbc:h2:file:./db;IFEXISTS=TRUE");
        assertThat(LlmSettingsEnvironmentPostProcessor.ifExists("jdbc:postgresql://h/db")).isEqualTo("jdbc:postgresql://h/db");
        assertThat(LlmSettingsEnvironmentPostProcessor.ifExists("jdbc:h2:mem:x")).isEqualTo("jdbc:h2:mem:x");
    }
}
