package com.lynra.kafkatower.agents.core.root;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.callback.AfterToolCallContext;
import com.embabel.chat.ToolCall;
import com.lynra.kafkatower.agents.core.audit.ToolCallEvidenceCollector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnrichedSubAgentToolsTest {

    @Mock
    private SubAgentTools delegate;

    @Test
    void withPriorFindingsReturnsQuestionUnchangedWhenNoSpecialistCalledYet() {
        EnrichedSubAgentTools tools = new EnrichedSubAgentTools(delegate, new ToolCallEvidenceCollector());

        assertThat(tools.withPriorFindings("what is the status?")).isEqualTo("what is the status?");
    }

    @Test
    void withPriorFindingsPrependsAnEarlierSpecialistCallToTheNextOne() {
        ToolCallEvidenceCollector evidence = new ToolCallEvidenceCollector();
        evidence.afterToolCall(new AfterToolCallContext(
                new ToolCall("call-1", "askMetrics", "{\"question\":\"cluster status\"}"),
                Tool.Result.text("no metrics data available for nonprod"),
                "no metrics data available for nonprod",
                42L));
        EnrichedSubAgentTools tools = new EnrichedSubAgentTools(delegate, evidence);

        String enriched = tools.withPriorFindings("is the cluster reachable?");

        assertThat(enriched)
                .contains("Findings from earlier specialist calls this conversation")
                .contains("Tool: askMetrics")
                .contains("no metrics data available for nonprod")
                .contains("is the cluster reachable?");
    }

    @Test
    void withPriorFindingsExcludesNonSpecialistToolCalls() {
        ToolCallEvidenceCollector evidence = new ToolCallEvidenceCollector();
        evidence.afterToolCall(new AfterToolCallContext(
                new ToolCall("call-1", "listClusters", "{\"query\":\"nonprod\"}"),
                Tool.Result.text("'nonprod' [registry: yes, live: yes]"),
                "'nonprod' [registry: yes, live: yes]",
                5L));
        EnrichedSubAgentTools tools = new EnrichedSubAgentTools(delegate, evidence);

        assertThat(tools.withPriorFindings("is the cluster reachable?")).isEqualTo("is the cluster reachable?");
    }

    @Test
    void askMetricsDelegatesWithTheEnrichedQuestion() {
        ToolCallEvidenceCollector evidence = new ToolCallEvidenceCollector();
        evidence.afterToolCall(new AfterToolCallContext(
                new ToolCall("call-1", "askInvestigation", "{\"question\":\"cluster status\"}"),
                Tool.Result.text("cluster is reachable"),
                "cluster is reachable",
                10L));
        EnrichedSubAgentTools tools = new EnrichedSubAgentTools(delegate, evidence);
        when(delegate.askMetrics(org.mockito.ArgumentMatchers.contains("cluster is reachable")))
                .thenReturn("metrics answer");

        String result = tools.askMetrics("what is the throughput?");

        assertThat(result).isEqualTo("metrics answer");
        verify(delegate).askMetrics(org.mockito.ArgumentMatchers.contains("Findings from earlier specialist calls"));
    }

    @Test
    void listClustersDelegatesUnchanged() {
        EnrichedSubAgentTools tools = new EnrichedSubAgentTools(delegate, new ToolCallEvidenceCollector());
        when(delegate.listClusters("nonprod")).thenReturn("cluster list");

        assertThat(tools.listClusters("nonprod")).isEqualTo("cluster list");
        verify(delegate).listClusters("nonprod");
    }
}
