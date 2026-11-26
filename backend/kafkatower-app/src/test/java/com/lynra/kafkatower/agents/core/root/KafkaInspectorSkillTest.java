package com.lynra.kafkatower.agents.core.root;

import com.lynra.kafkatower.utils.AgentSkills;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaInspectorSkillTest {

    @Test
    void itsRichOutputSkillShipsAndCanBeActivatedOnDemand() {
        AgentSkills agentSkills = new AgentSkills();
        try {
            assertThat(agentSkills.reference(KafkaInspector.RICH_OUTPUT_SKILL).activate(KafkaInspector.RICH_OUTPUT_SKILL))
                    .contains("```mermaid").contains("```chart");
        } finally {
            agentSkills.destroy();
        }
    }
}
