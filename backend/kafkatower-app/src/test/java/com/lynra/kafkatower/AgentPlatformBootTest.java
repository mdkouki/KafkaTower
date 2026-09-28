package com.lynra.kafkatower;

import com.embabel.agent.core.Agent;
import com.embabel.agent.core.AgentPlatform;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real production Spring context and asserts every production agent deployed cleanly.
 * <p>
 * Uses inline H2 {@code properties} instead of {@code @ActiveProfiles("test")}: Embabel's own
 * agent-deploying bean is annotated {@code @Profile("!test")}, so that profile name silently
 * disables deployment. Observability is disabled here because this is the only other
 * full-context {@code @SpringBootTest} besides {@code goap.GoapSpikeTest}, and both would
 * otherwise race to call the JVM-wide {@code GlobalOpenTelemetry.set(...)} in the same Surefire
 * JVM. See EMBABEL_GOAP_ALIGNMENT_PLAN.md for the full history.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "ECGPT_API_KEY=test-key-not-used-no-llm-calls-in-this-boot-test",
                "embabel.agent.platform.observability.enabled=false",
                // Same override as application-test.yml, applied directly instead of via
                // @ActiveProfiles("test") — see class Javadoc for why that profile name must be avoided.
                "spring.datasource.url=jdbc:h2:mem:agentplatformboottestdb;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.jpa.hibernate.ddl-auto=create-drop"
        })
class AgentPlatformBootTest {

    @Autowired
    private AgentPlatform agentPlatform;

    @Test
    void allProductionAgentsDeployAtStartup() {
        List<Agent> agents = agentPlatform.agents();
        Set<String> names = agents.stream().map(Agent::getName).collect(java.util.stream.Collectors.toSet());

        // KafkaLogInspector is intentionally excluded here — it's @ConditionalOnProperty
        // (agent.mcp.os.enabled), off by default, matching this test's environment where no
        // OpenSearch is configured, same as production without that feature turned on.
        assertThat(names).contains("KafkaInspector", "KafkaMetricsInspector", "KafkaInvestigator");
    }
}
