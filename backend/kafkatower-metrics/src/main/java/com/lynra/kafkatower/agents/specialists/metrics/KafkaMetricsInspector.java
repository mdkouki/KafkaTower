package com.lynra.kafkatower.agents.specialists.metrics;

import com.lynra.kafkatower.config.ConditionalOnAssistantEnabled;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.tool.progressive.UnfoldingTool;
import com.lynra.kafkatower.agents.core.audit.LlmCallTimer;
import com.lynra.kafkatower.agents.core.audit.ToolCallAuditLogger;
import com.lynra.kafkatower.agents.core.specialist.Specialist;
import com.lynra.kafkatower.agents.specialists.config.MetricsBackend;
import com.lynra.kafkatower.agents.core.guardrails.NoSecretLeakageGuardRail;
import com.lynra.kafkatower.collections.LargeResultGuard;
import com.lynra.kafkatower.tools.MetricsSkills;
import com.lynra.kafkatower.utils.AgentSkills;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

@ConditionalOnAssistantEnabled
@Agent(description = "Answers historical time-series questions about Kafka metrics: " +
        "trends, throughput, consumer lag charts, capacity analysis, and comparisons over a time window. " +
        "Use this agent when the question asks for a chart, trend, or historical comparison. Also use it to " +
        "narrow a vague or wide-open timeframe (e.g. find when a lag spike or throughput drop started) " +
        "before handing a precise window to another agent.")
public class KafkaMetricsInspector implements Specialist<MetricsQuestion, MetricsAnswer> {

    private static final Logger log = LoggerFactory.getLogger(KafkaMetricsInspector.class);
    static final String SKILL = "kafka-metrics-inspector";

    private final AgentSkills agentSkills;
    private final LargeResultGuard largeResultGuard;
    private final MetricsBackend metricsBackend;
    private final long slowCallThresholdMs;
    private volatile String sharedGuidance;

    public KafkaMetricsInspector(AgentSkills agentSkills,
                                  LargeResultGuard largeResultGuard,
                                  MetricsBackend metricsBackend,
                                  @Value("${agent.llm.slow-call-warning-ms:15000}") long slowCallThresholdMs) {
        this.agentSkills = agentSkills;
        this.largeResultGuard = largeResultGuard;
        this.metricsBackend = metricsBackend;
        this.slowCallThresholdMs = slowCallThresholdMs;
    }

    @Override
    public void applySharedGuidance(String sharedGuidance) {
        this.sharedGuidance = sharedGuidance;
    }

    private String systemPrompt() {
        return agentSkills.systemPrompt(SKILL, sharedGuidance);
    }

    @AchievesGoal(description = "Kafka metrics question answered")
    @Action(description = """
            Answer a historical time-series question about Kafka from the metrics store.
            Covers: consumer lag trends, broker throughput, partition health over time,
            capacity analysis, and metric comparisons across a time window.
            The answer is derived from time-series data, not live broker state.
            """)
    public MetricsAnswer answer(MetricsQuestion question, Ai ai) {
        if (!metricsBackend.isAvailable()) {
            return new MetricsAnswer(metricsBackend.unavailableMessage());
        }
        UnfoldingTool metricsTools = metricsBackend.queryTools(
                "metrics-query",
                "Kafka time-series data",
                "Start with `metrics` (discovery, at most once) using the narrowest pattern for "
                        + "the question, then `query` (instant, default) or `query_range` (only for "
                        + "an explicit trend/history request), per the workflow in your skill.");
        // Non-streaming on purpose: the streaming call path (StreamingLlmOperationsImpl)
        // resolves its tool list once via ToolResolutionHelper and hands it to the raw LLM
        // stream — it never re-evaluates ToolInjectionStrategy between rounds. UnfoldingTool's
        // facade-then-real-tools swap depends on that per-round re-evaluation (DefaultToolLoop
        // does it; the streaming path doesn't), so over streaming the LLM only ever sees the
        // "vm-metrics" facade and can loop on it indefinitely without ever reaching a real
        // tool. This specialist's own token stream is suppressed by the caller anyway
        // (SubAgentTools.run), so streaming bought nothing here — createObject uses the
        // ordinary tool loop where Unfolding actually works.
        String response = LlmCallTimer.timed(log, "metrics answer", slowCallThresholdMs, () ->
                largeResultGuard.answer(ai.withAutoLlm()
                        .withSystemPrompt(systemPrompt())
                        .withReference(agentSkills.reference(MetricsSkills.QUERIES, MetricsSkills.CATALOG))
                        .withTool(metricsTools)
                        .withToolCallInspectors(ToolCallAuditLogger.INSTANCE)
                        .withGuardRails(NoSecretLeakageGuardRail.INSTANCE),
                        question.text()));
        return new MetricsAnswer(response);
    }

    @Override
    public String name() {
        return KafkaMetricsInspector.class.getSimpleName();
    }

    @Override
    public String toolName() {
        return "askMetrics";
    }

    @Override
    public Class<MetricsAnswer> answerType() {
        return MetricsAnswer.class;
    }

    @Override
    public MetricsQuestion question(String text) {
        return new MetricsQuestion(text);
    }

    @Override
    public String text(MetricsAnswer answer) {
        return answer.text();
    }
}
