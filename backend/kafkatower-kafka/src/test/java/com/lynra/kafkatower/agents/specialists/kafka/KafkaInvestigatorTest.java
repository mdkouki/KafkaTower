package com.lynra.kafkatower.agents.specialists.kafka;

import com.embabel.agent.api.common.Ai;
import com.lynra.kafkatower.agents.specialists.config.MetricsBackend;
import com.lynra.kafkatower.collections.LargeResultGuard;
import com.lynra.kafkatower.tools.KafkaAdminClientTools;
import com.lynra.kafkatower.utils.AgentSkills;
import com.lynra.kafkatower.utils.CachedTools;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KafkaInvestigatorTest {

    private final MetricsBackend metricsBackend = mock(MetricsBackend.class);
    private final Ai ai = mock(Ai.class);
    private final KafkaInvestigator investigator = new KafkaInvestigator(mock(KafkaAdminClientTools.class),
            mock(AgentSkills.class), mock(CachedTools.class), mock(LargeResultGuard.class), metricsBackend, 15_000L);
    private final InvestigationQuestion question = new InvestigationQuestion("how many topics?");

    @Test
    void metricsStepIsSkippedWithoutAnyLlmCallWhenTheAdminFindingsFullyAnswerTheQuestion() {
        MetricsCorrelation correlation = investigator.correlateMetrics(question, new AdminSnapshot("18 topics", false), ai);

        assertThat(correlation.summary()).contains("no metric history is needed");
        verifyNoInteractions(ai, metricsBackend);
    }

    @Test
    void metricsStepIsSkippedWithoutAnyLlmCallWhenTheMetricsBackendIsUnavailable() {
        when(metricsBackend.isAvailable()).thenReturn(false);
        when(metricsBackend.unavailableMessage()).thenReturn("The metrics service is currently unreachable.");

        MetricsCorrelation correlation = investigator.correlateMetrics(question, new AdminSnapshot("lag 4000", true), ai);

        assertThat(correlation.summary()).contains("The metrics service is currently unreachable.")
                .contains("could not be checked");
        verifyNoInteractions(ai);
    }

    private static final String TOOL_CALL = "[Tool Call]\n```json\n{\"tool_name\": \"listTopics\"}\n```";
    private static final String REPORT = "## Kafka Investigation Report\n1. [Admin] 6 topics.";

    private final AdminSnapshot snapshot = new AdminSnapshot("There are 6 topics containing fulfillment.", true);
    private final MetricsCorrelation correlation = new MetricsCorrelation("Not run: no metric history is needed.");

    @Test
    void aProperReportIsReturnedFromASingleLlmCall() {
        List<String> prompts = new ArrayList<>();

        String result = investigator.synthesizeValidated("prompt", snapshot, correlation, text -> {
            prompts.add(text);
            return REPORT;
        });

        assertThat(result).isEqualTo(REPORT);
        assertThat(prompts).containsExactly("prompt");
    }

    @Test
    void aToolCallWrittenAsTextIsRetriedOnceWithTheMistakeNamed() {
        List<String> prompts = new ArrayList<>();

        String result = investigator.synthesizeValidated("prompt", snapshot, correlation, text -> {
            prompts.add(text);
            return prompts.size() == 1 ? TOOL_CALL : REPORT;
        });

        assertThat(result).isEqualTo(REPORT);
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(1)).startsWith("prompt").contains("tool call written out as text");
    }

    @Test
    void aRepeatedToolCallFallsBackToTheGatheredFindingsInsteadOfPassingTheToolCallUpstream() {
        List<String> prompts = new ArrayList<>();

        String result = investigator.synthesizeValidated("prompt", snapshot, correlation, text -> {
            prompts.add(text);
            return TOOL_CALL;
        });

        assertThat(prompts).hasSize(2);
        assertThat(result).startsWith("## Kafka Investigation Report")
                .contains("There are 6 topics containing fulfillment.")
                .contains("Not run: no metric history is needed.")
                .doesNotContain("tool_name");
    }
}
