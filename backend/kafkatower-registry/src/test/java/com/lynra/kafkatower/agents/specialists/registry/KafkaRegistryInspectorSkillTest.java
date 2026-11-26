package com.lynra.kafkatower.agents.specialists.registry;

import com.lynra.kafkatower.utils.AgentSkills;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaRegistryInspectorSkillTest {

    @Test
    void itsInstructionsAreInlinedAheadOfTheSharedGuidance() {
        AgentSkills agentSkills = new AgentSkills();
        try {
            assertThat(agentSkills.systemPrompt(KafkaRegistryInspector.SKILL, "Shared guidance."))
                    .startsWith("# Kafka Registry Inspector").contains("whoUsesTopic").endsWith("Shared guidance.");
        } finally {
            agentSkills.destroy();
        }
    }
}
