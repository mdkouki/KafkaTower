package com.lynra.kafkatower.agents.specialists.log;

import com.lynra.kafkatower.utils.AgentSkills;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaLogInspectorSkillTest {

    @Test
    void itsInstructionsAreInlinedAheadOfTheSharedGuidance() {
        AgentSkills agentSkills = new AgentSkills();
        try {
            assertThat(agentSkills.systemPrompt(KafkaLogInspector.SKILL, "Shared guidance."))
                    .startsWith("# Kafka Log Inspector").contains("aggregate_documents").endsWith("Shared guidance.");
        } finally {
            agentSkills.destroy();
        }
    }
}
