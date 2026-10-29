package com.lynra.kafkatower.goap;

import com.lynra.kafkatower.config.ConfiguredModelLoader;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Test-only Spring Boot entry point for {@link GoapSpikeTest}. Deliberately lives in this
 * sub-package (not {@code com.lynra.kafkatower}) so component scanning never picks up the
 * real application's beans (e.g. {@code ChatService}, the production {@code KafkaInspector}
 * agent) — this test only needs Embabel's auto-configured {@code AgentPlatform}/
 * {@code AgentMetadataReader} beans, which are wired via Spring Boot auto-configuration
 * (independent of component-scan base package), not the rest of this application.
 * <p>
 * The one exception is {@link ConfiguredModelLoader}, imported explicitly: Embabel refuses to start
 * unless {@code embabel.models.default-llm} is a registered model, and that loader is what
 * registers the configured one.
 */
@SpringBootApplication
@Import(ConfiguredModelLoader.class)
class GoapSpikeTestApplication {
}
