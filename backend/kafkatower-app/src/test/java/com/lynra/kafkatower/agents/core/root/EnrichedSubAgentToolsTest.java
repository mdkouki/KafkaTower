package com.lynra.kafkatower.agents.core.root;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.callback.AfterToolCallContext;
import com.embabel.chat.ToolCall;
import com.lynra.kafkatower.agents.core.audit.ToolCallEvidenceCollector;
import com.lynra.kafkatower.agents.core.specialist.Specialist;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnrichedSubAgentToolsTest {

    private static final Set<String> SPECIALIST_TOOLS = Set.of("askMetrics", "askInvestigation", "askRegistry");

    @Mock
    private SubAgentTools delegate;

    @Mock
    private Specialist<Object, Object> metrics;

    @Mock
    private Specialist<Object, Object> registry;

    @Test
    void withPriorFindingsReturnsQuestionUnchangedWhenNoSpecialistCalledYet() {
        EnrichedSubAgentTools tools = new EnrichedSubAgentTools(delegate, new ToolCallEvidenceCollector(SPECIALIST_TOOLS));

        assertThat(tools.withPriorFindings("what is the status?")).isEqualTo("what is the status?");
    }

    @Test
    void withPriorFindingsPrependsAnEarlierSpecialistCallToTheNextOne() {
        ToolCallEvidenceCollector evidence = new ToolCallEvidenceCollector(SPECIALIST_TOOLS);
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
        ToolCallEvidenceCollector evidence = new ToolCallEvidenceCollector(SPECIALIST_TOOLS);
        evidence.afterToolCall(new AfterToolCallContext(
                new ToolCall("call-1", "listClusters", "{\"query\":\"nonprod\"}"),
                Tool.Result.text("'nonprod' [registry: yes, live: yes]"),
                "'nonprod' [registry: yes, live: yes]",
                5L));
        EnrichedSubAgentTools tools = new EnrichedSubAgentTools(delegate, evidence);

        assertThat(tools.withPriorFindings("is the cluster reachable?")).isEqualTo("is the cluster reachable?");
    }

    @Test
    void everyDeployedSpecialistBecomesOneToolNamedAndDescribedByItself() {
        when(metrics.toolName()).thenReturn("askMetrics");
        when(delegate.toolDescription(metrics)).thenReturn("metrics description");
        when(registry.toolName()).thenReturn("askRegistry");
        when(delegate.toolDescription(registry)).thenReturn("registry description");
        when(delegate.specialists()).thenReturn(List.of(metrics, registry));
        EnrichedSubAgentTools tools = new EnrichedSubAgentTools(delegate, new ToolCallEvidenceCollector(SPECIALIST_TOOLS));

        List<Tool> specialistTools = tools.specialistTools();

        assertThat(specialistTools).extracting(t -> t.getDefinition().getName())
                .containsExactly("askMetrics", "askRegistry");
        assertThat(specialistTools).extracting(t -> t.getDefinition().getDescription())
                .containsExactly("metrics description", "registry description");
    }

    @Test
    void noSpecialistsDeployedMeansNoSpecialistTools() {
        when(delegate.specialists()).thenReturn(List.of());
        EnrichedSubAgentTools tools = new EnrichedSubAgentTools(delegate, new ToolCallEvidenceCollector(SPECIALIST_TOOLS));

        assertThat(tools.specialistTools()).isEmpty();
    }

    @Test
    void specialistToolDelegatesWithTheQuestionAndPriorFindings() {
        ToolCallEvidenceCollector evidence = new ToolCallEvidenceCollector(SPECIALIST_TOOLS);
        evidence.afterToolCall(new AfterToolCallContext(
                new ToolCall("call-1", "askInvestigation", "{\"question\":\"cluster status\"}"),
                Tool.Result.text("cluster is reachable"),
                "cluster is reachable",
                10L));
        when(registry.toolName()).thenReturn("askRegistry");
        when(delegate.toolDescription(registry)).thenReturn("registry description");
        when(delegate.specialists()).thenReturn(List.of(registry));
        when(delegate.ask(eq(registry), contains("cluster is reachable"))).thenReturn("registry answer");
        EnrichedSubAgentTools tools = new EnrichedSubAgentTools(delegate, evidence);

        Tool.Result result = tools.specialistTools().get(0).call("{\"question\":\"who uses inventory.snapshots?\"}");

        assertThat(result).isInstanceOf(Tool.Result.Text.class);
        assertThat(((Tool.Result.Text) result).getContent()).isEqualTo("registry answer");
        verify(delegate).ask(eq(registry), contains("who uses inventory.snapshots?"));
    }

    @Test
    void specialistFindingsFromAnyDeployedSpecialistAreForwardedIncludingRegistry() {
        ToolCallEvidenceCollector evidence = new ToolCallEvidenceCollector(SPECIALIST_TOOLS);
        evidence.afterToolCall(new AfterToolCallContext(
                new ToolCall("call-1", "askRegistry", "{\"question\":\"who uses it?\"}"),
                Tool.Result.text("order-service produces"),
                "order-service produces",
                10L));
        EnrichedSubAgentTools tools = new EnrichedSubAgentTools(delegate, evidence);

        assertThat(tools.withPriorFindings("is it active?")).contains("Tool: askRegistry").contains("order-service produces");
    }

    @Test
    void listClustersDelegatesUnchanged() {
        EnrichedSubAgentTools tools = new EnrichedSubAgentTools(delegate, new ToolCallEvidenceCollector(SPECIALIST_TOOLS));
        when(delegate.listClusters("nonprod")).thenReturn("cluster list");

        assertThat(tools.listClusters("nonprod")).isEqualTo("cluster list");
        verify(delegate).listClusters("nonprod");
    }
}
