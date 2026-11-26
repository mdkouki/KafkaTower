package com.lynra.kafkatower.agents.specialists.metrics;

import com.embabel.agent.skills.Skills;
import com.lynra.kafkatower.tools.MetricsSkills;
import com.lynra.kafkatower.utils.AgentSkills;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaMetricsInspectorSkillTest {

    @Test
    void itsCoreInstructionsAreInlinedAheadOfTheSharedGuidance() {
        AgentSkills agentSkills = new AgentSkills();
        try {
            assertThat(agentSkills.systemPrompt(KafkaMetricsInspector.SKILL, "Shared guidance."))
                    .startsWith("# Kafka Metrics Inspector").contains("Hard constraints").endsWith("Shared guidance.");
        } finally {
            agentSkills.destroy();
        }
    }

    @Test
    void onlyTheQueryAndCatalogSkillsAreOfferedOnDemandAndEveryReferenceIsReadable() {
        AgentSkills agentSkills = new AgentSkills();
        try {
            Skills reference = agentSkills.reference(MetricsSkills.QUERIES, MetricsSkills.CATALOG);

            assertThat(reference.notes()).contains(MetricsSkills.QUERIES, MetricsSkills.CATALOG)
                    .doesNotContain(KafkaMetricsInspector.SKILL);
            for (String file : new String[]{"replication-health.md", "broker-saturation.md", "storage-and-traffic.md"}) {
                assertThat(reference.readResource(MetricsSkills.QUERIES, "references", file)).contains("promql");
            }
            for (String file : new String[]{"kminion.md", "kafka-core.md", "kafka-extended.md"}) {
                assertThat(reference.readResource(MetricsSkills.CATALOG, "references", file)).startsWith("# Catalog");
            }
        } finally {
            agentSkills.destroy();
        }
    }
}
