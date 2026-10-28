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

    @Test
    void savedOpenSearchAndMetricsSectionsOverrideTheYmlProperties() throws Exception {
        String url = newDatabase();
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE admin_settings (section VARCHAR(32) PRIMARY KEY, payload CLOB)");
            s.execute("INSERT INTO admin_settings VALUES ('opensearch', "
                    + quote("{\"enabled\":true,\"defaultCluster\":\"prod\",\"clusters\":[{\"name\":\"prod\","
                    + "\"url\":\"https://os:9200\",\"username\":\"u\",\"password\":\"p\",\"index\":\"logs-*\"}]}") + ")");
            s.execute("INSERT INTO admin_settings VALUES ('metrics', "
                    + quote("{\"url\":\"http://vm:8428\",\"token\":\"t\"}") + ")");
        }

        MockEnvironment env = run(url);

        assertThat(env.getProperty("agent.mcp.os.enabled")).isEqualTo("true");
        assertThat(env.getProperty("agent.mcp.os.default-cluster")).isEqualTo("prod");
        assertThat(env.getProperty("agent.mcp.os.clusters[prod].url")).isEqualTo("https://os:9200");
        assertThat(env.getProperty("agent.mcp.os.clusters[prod].index")).isEqualTo("logs-*");
        assertThat(env.getProperty("agent.metrics.url")).isEqualTo("http://vm:8428");
        assertThat(env.getProperty("agent.metrics.token")).isEqualTo("t");
    }

    @Test
    void withoutSavedSectionsTheYmlPropertiesStayInEffect() throws Exception {
        MockEnvironment env = run(newDatabase());
        env.setProperty("agent.metrics.url", "http://yml:8428");

        assertThat(env.getProperty("agent.metrics.url")).isEqualTo("http://yml:8428");
    }

    @Test
    void savedKafkaClustersBecomeKafkaClusterProperties() throws Exception {
        String url = newDatabase();
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE admin_settings (section VARCHAR(32) PRIMARY KEY, payload CLOB)");
            s.execute("INSERT INTO admin_settings VALUES ('kafka', " + quote("{\"clusters\":["
                    + "{\"name\":\"plain\",\"bootstrapServers\":\"b:9092\",\"security\":\"NONE\"},"
                    + "{\"name\":\"tls\",\"bootstrapServers\":\"b:9093\",\"security\":\"MTLS\",\"ssl\":"
                    + "{\"keystoreLocation\":\"/k.p12\",\"keystorePassword\":\"kp\",\"keystoreType\":\"PKCS12\","
                    + "\"truststoreLocation\":\"/t.p12\"}},"
                    + "{\"name\":\"sasl\",\"bootstrapServers\":\"b:9094\",\"security\":\"SASL\",\"sasl\":"
                    + "{\"mechanism\":\"SCRAM-SHA-512\",\"username\":\"u\",\"password\":\"p\",\"tls\":true},"
                    + "\"ssl\":{\"truststoreLocation\":\"/t.p12\"}}]}") + ")");
        }

        MockEnvironment env = run(url);

        assertThat(env.getProperty("kafka.clusters[plain].bootstrap-servers")).isEqualTo("b:9092");
        assertThat(env.getProperty("kafka.clusters[plain].security-protocol")).isEqualTo("PLAINTEXT");
        assertThat(env.getProperty("kafka.clusters[tls].security-protocol")).isEqualTo("SSL");
        assertThat(env.getProperty("kafka.clusters[tls].ssl.keystore-location")).isEqualTo("/k.p12");
        assertThat(env.getProperty("kafka.clusters[tls].ssl.keystore-password")).isEqualTo("kp");
        assertThat(env.getProperty("kafka.clusters[sasl].security-protocol")).isEqualTo("SASL_SSL");
        assertThat(env.getProperty("kafka.clusters[sasl].sasl.mechanism")).isEqualTo("SCRAM-SHA-512");
        assertThat(env.getProperty("kafka.clusters[sasl].sasl.username")).isEqualTo("u");
        assertThat(env.getProperty("kafka.clusters[sasl].ssl.truststore-location")).isEqualTo("/t.p12");
    }

    @Test
    void anUnreadableSavedSectionDoesNotStopStartup() throws Exception {
        String url = newDatabase();
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE admin_settings (section VARCHAR(32) PRIMARY KEY, payload CLOB)");
            // what a large-object OID left in the column looks like
            s.execute("INSERT INTO admin_settings VALUES ('metrics', '16455')");
            s.execute("INSERT INTO admin_settings VALUES ('opensearch', 'not json')");
            s.execute("INSERT INTO admin_settings VALUES ('kafka', '{broken')");
        }

        MockEnvironment env = run(url);

        assertThat(env.getProperty("agent.metrics.url")).isNull();
        assertThat(env.getProperty("agent.mcp.os.enabled")).isNull();
    }
}
