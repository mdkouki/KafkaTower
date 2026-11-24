package com.lynra.kafkatower.agents.core.audit;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.callback.AfterToolCallContext;
import com.embabel.chat.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallEvidenceCollectorTest {

    private final ToolCallEvidenceCollector collector = new ToolCallEvidenceCollector(Set.of("askMetrics", "askLogs"));

    private void record(String toolName, String arguments, String result) {
        collector.afterToolCall(new AfterToolCallContext(
                new ToolCall("id", toolName, arguments), Tool.Result.text(result), result, 1L));
    }

    @Test
    void transcriptSaysSoWhenNoToolWasCalled() {
        assertThat(collector.transcript()).isEqualTo("(no specialist tools were called for this answer)");
    }

    @Test
    void transcriptListsEveryCallWithArgumentsAndResult() {
        record("listClusters", "{}", "prod");
        record("askMetrics", "{\"q\":\"lag\"}", "lag is 5");

        assertThat(collector.transcript())
                .contains("Tool: listClusters\nArgs: {}\nResult: prod")
                .contains("Tool: askMetrics\nArgs: {\"q\":\"lag\"}\nResult: lag is 5");
    }

    @Test
    void findingsAreNullWhenNoSpecialistWasCalled() {
        record("listClusters", "{}", "prod");

        assertThat(collector.specialistFindingsSoFar()).isNull();
    }

    @Test
    void findingsContainOnlySpecialistCalls() {
        record("listClusters", "{}", "prod");
        record("askLogs", "{}", "no errors");

        assertThat(collector.specialistFindingsSoFar())
                .contains("Tool: askLogs")
                .doesNotContain("listClusters");
    }

    @Test
    void findingsDoNotMatchToolNamesThatMerelyStartWithASpecialistName() {
        record("askMetricsExtra", "{}", "x");

        assertThat(collector.specialistFindingsSoFar()).isNull();
    }

    @Test
    void oldestCallsAreElidedWhenTheTranscriptExceedsItsBudget() {
        record("askMetrics", "{}", "first-" + "a".repeat(15_000));
        record("askMetrics", "{}", "second-" + "b".repeat(15_000));
        record("askMetrics", "{}", "third-" + "c".repeat(100));

        String transcript = collector.transcript();

        assertThat(transcript).startsWith("[1 older tool call(s) elided]")
                .contains("third-")
                .doesNotContain("first-");
    }

    @Test
    void theNewestCallIsKeptEvenWhenItAloneExceedsTheBudget() {
        record("askMetrics", "{}", "x".repeat(30_000));

        assertThat(collector.specialistFindingsSoFar()).contains("x".repeat(30_000)).doesNotContain("elided");
    }
}
