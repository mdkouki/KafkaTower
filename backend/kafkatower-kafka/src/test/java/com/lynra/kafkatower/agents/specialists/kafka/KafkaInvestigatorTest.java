package com.lynra.kafkatower.agents.specialists.kafka;

import com.embabel.agent.api.common.Ai;
import com.lynra.kafkatower.agents.specialists.config.MetricsBackend;
import com.lynra.kafkatower.tools.BulkCollectionTools;
import com.lynra.kafkatower.tools.KafkaAdminClientTools;
import com.lynra.kafkatower.tools.MetricsSkillTools;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KafkaInvestigatorTest {

    private final MetricsBackend metricsBackend = mock(MetricsBackend.class);
    private final Ai ai = mock(Ai.class);
    private final KafkaInvestigator investigator = new KafkaInvestigator(mock(KafkaAdminClientTools.class),
            mock(MetricsSkillTools.class), mock(BulkCollectionTools.class), metricsBackend, 15_000L);
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
}
