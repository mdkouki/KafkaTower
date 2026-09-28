package com.lynra.kafkatower.goap;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test-only Spring Boot entry point for {@link GoapSpikeTest}. Deliberately lives in this
 * sub-package (not {@code com.lynra.kafkatower}) so component scanning never picks up the
 * real application's beans (e.g. {@code ChatService}, the production {@code KafkaInspector}
 * agent) — this test only needs Embabel's auto-configured {@code AgentPlatform}/
 * {@code AgentMetadataReader} beans, which are wired via Spring Boot auto-configuration
 * (independent of component-scan base package), not the rest of this application.
 */
@SpringBootApplication
class GoapSpikeTestApplication {
}
