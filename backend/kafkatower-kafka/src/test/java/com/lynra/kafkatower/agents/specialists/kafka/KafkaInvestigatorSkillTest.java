package com.lynra.kafkatower.agents.specialists.kafka;

import com.embabel.agent.skills.Skills;
import com.lynra.kafkatower.tools.MetricsSkills;
import com.lynra.kafkatower.utils.AgentSkills;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaInvestigatorSkillTest {

    @Test
    void itsCoreInstructionsAreInlinedWithTheAdminToolRulesAheadOfTheSharedGuidance() {
        AgentSkills agentSkills = new AgentSkills();
        try {
            assertThat(agentSkills.systemPrompt(KafkaInvestigator.INVESTIGATOR_SKILL, "Shared guidance."))
                    .startsWith("# Kafka Investigator").contains("## Admin tool usage")
                    .contains("`listTopics`, `listConsumerGroups` need a regex").doesNotContain("## Kafka Investigation Report")
                    .endsWith("Shared guidance.");
        } finally {
            agentSkills.destroy();
        }
    }

    @Test
    void theToolLessSynthesisStepGetsOnlyTheReportFormatNotTheWholeInvestigatorSkill() {
        AgentSkills agentSkills = new AgentSkills();
        try {
            String prompt = agentSkills.systemPrompt(KafkaInvestigator.REPORT_SKILL, "Shared guidance.");

            assertThat(prompt).startsWith("# Kafka Investigation Report").contains("### Root Cause")
                    .doesNotContain("Entity scale").endsWith("Shared guidance.");
        } finally {
            agentSkills.destroy();
        }
    }

    @Test
    void playbooksCatalogAndAdminReferenceAreOfferedOnDemandAndEveryReferenceIsReadable() {
        AgentSkills agentSkills = new AgentSkills();
        try {
            Skills reference = agentSkills.reference(MetricsSkills.INVESTIGATION_PLAYBOOKS, MetricsSkills.CATALOG,
                    KafkaInvestigator.ADMIN_REFERENCE_SKILL);

            assertThat(reference.notes()).contains(MetricsSkills.INVESTIGATION_PLAYBOOKS, MetricsSkills.CATALOG,
                    KafkaInvestigator.ADMIN_REFERENCE_SKILL)
                    .doesNotContain(KafkaInvestigator.INVESTIGATOR_SKILL).doesNotContain(KafkaInvestigator.REPORT_SKILL);
            for (String playbook : new String[]{"cluster.md", "broker.md", "topic.md", "consumer-group.md",
                    "principal.md", "failure-patterns.md"}) {
                assertThat(reference.readResource(MetricsSkills.INVESTIGATION_PLAYBOOKS, "references", playbook))
                        .startsWith("# Playbook");
            }
            for (String file : new String[]{"consumer-group-states.md", "acls.md", "key-configs.md"}) {
                assertThat(reference.readResource(KafkaInvestigator.ADMIN_REFERENCE_SKILL, "references", file))
                        .startsWith("# Kafka Admin Reference");
            }
        } finally {
            agentSkills.destroy();
        }
    }
}
