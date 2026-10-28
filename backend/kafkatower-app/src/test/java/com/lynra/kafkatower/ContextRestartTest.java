package com.lynra.kafkatower;

import com.lynra.kafkatower.config.ApplicationRestarter;
import com.lynra.kafkatower.model.LlmSettings;
import com.lynra.kafkatower.repository.LlmSettingsRepository;
import io.opentelemetry.api.GlobalOpenTelemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The whole "configure the LLM in the UI" flow, without a process restart: the app starts with no
 * LLM settings (assistant off), the settings are saved and the context restarts itself (assistant
 * on), then a setting changes and it restarts again — the case where Embabel's observability would
 * otherwise try to register OpenTelemetry globally a second time and refuse to start.
 */
class ContextRestartTest {

    @AfterEach
    void cleanUp() {
        ConfigurableApplicationContext latest = RestartProbe.latest;
        if (latest != null && latest.isActive()) latest.close();
        RestartProbe.latest = null;
        GlobalOpenTelemetry.resetForTest();
    }

    private ConfigurableApplicationContext awaitNewContext(ConfigurableApplicationContext previous) {
        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofMillis(200))
                .until(() -> RestartProbe.latest != null && RestartProbe.latest != previous
                        && RestartProbe.latest.isActive());
        return RestartProbe.latest;
    }

    private void saveModel(ConfigurableApplicationContext context, String model) {
        LlmSettingsRepository repo = context.getBean(LlmSettingsRepository.class);
        LlmSettings settings = repo.findById(1L).orElseGet(LlmSettings::new);
        settings.setApiKey("sk-test-key");
        settings.setBaseUrl("http://localhost:1234");
        settings.setModel(model);
        repo.save(settings);
    }

    @Test
    void savingTheLlmSettingsRestartsTheContextWithTheAssistantOn() {
        ConfigurableApplicationContext first = SpringApplication.run(KafkaTowerApplication.class,
                "--restart.probe=true",
                "--spring.main.web-application-type=none",
                "--spring.datasource.url=jdbc:h2:mem:contextrestarttestdb;DB_CLOSE_DELAY=-1",
                "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.datasource.username=sa",
                "--spring.datasource.password=",
                "--spring.flyway.enabled=false",
                "--spring.jpa.hibernate.ddl-auto=update");
        assertThat(first.getEnvironment().getProperty("kafkatower.assistant.enabled")).isEqualTo("false");
        assertThat(first.containsBean("chatService")).isFalse();

        saveModel(first, "mistral-medium-2508");
        first.getBean(ApplicationRestarter.class).restart();
        ConfigurableApplicationContext second = awaitNewContext(first);

        assertThat(first.isActive()).isFalse();
        assertThat(second.getEnvironment().getProperty("kafkatower.assistant.enabled")).isEqualTo("true");
        assertThat(second.getEnvironment().getProperty("embabel.models.default-llm")).isEqualTo("mistral-medium-2508");
        assertThat(second.containsBean("chatService")).isTrue();
        assertThat(second.containsBean("kafkaInspector")).isTrue();

        saveModel(second, "gpt-5.1");
        second.getBean(ApplicationRestarter.class).restart();
        ConfigurableApplicationContext third = awaitNewContext(second);

        assertThat(third.getEnvironment().getProperty("embabel.models.default-llm")).isEqualTo("gpt-5.1");
        assertThat(third.containsBean("chatService")).isTrue();
    }
}
