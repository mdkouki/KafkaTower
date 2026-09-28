package com.lynra.kafkatower.agents.specialists.metrics;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.tool.progressive.UnfoldingTool;
import com.lynra.kafkatower.agents.core.audit.LlmCallTimer;
import com.lynra.kafkatower.agents.core.audit.ToolCallAuditLogger;
import com.lynra.kafkatower.agents.core.specialist.Specialist;
import com.lynra.kafkatower.agents.specialists.config.McpHealthChecker;
import com.lynra.kafkatower.agents.specialists.config.VmMcpClientProvider;
import com.lynra.kafkatower.agents.core.guardrails.NoSecretLeakageGuardRail;
import com.lynra.kafkatower.tools.MetricsSkillTools;
import com.lynra.kafkatower.utils.SkillLoader;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

@Agent(description = "Answers historical time-series questions about Kafka metrics: " +
        "trends, throughput, consumer lag charts, capacity analysis, and comparisons over a time window. " +
        "Use this agent when the question asks for a chart, trend, or historical comparison.")
public class KafkaMetricsInspector implements Specialist<MetricsQuestion, MetricsAnswer> {

    private static final Logger log = LoggerFactory.getLogger(KafkaMetricsInspector.class);

    private final MetricsSkillTools metricsSkillTools;
    private final VmMcpClientProvider mcpClientProvider;
    private final McpHealthChecker mcpHealthChecker;
    private final long slowCallThresholdMs;
    private String basePrompt;
    private String systemPrompt;

    public KafkaMetricsInspector(MetricsSkillTools metricsSkillTools,
                                  VmMcpClientProvider mcpClientProvider,
                                  McpHealthChecker mcpHealthChecker,
                                  @Value("${agent.llm.slow-call-warning-ms:15000}") long slowCallThresholdMs) {
        this.metricsSkillTools = metricsSkillTools;
        this.mcpClientProvider = mcpClientProvider;
        this.mcpHealthChecker = mcpHealthChecker;
        this.slowCallThresholdMs = slowCallThresholdMs;
    }

    @PostConstruct
    void loadSkill() {
        // KAFKA_METRICS_INSPECTOR.md is a self-contained step-by-step workflow with its own
        // embedded PromQL for the common case (this agent has no AdminClient tools, so most of
        // the full investigation playbook — written for KafkaInvestigator's admin+metrics
        // workflow — doesn't apply here anyway). The metrics catalog is fetched on demand via
        // MetricsSkillTools.getMetricsCatalog(category) instead of being concatenated whole.
        // Cross-specialist shared guidance (query strategy, reply format, etc.) is NOT loaded
        // here — see applySharedGuidance below.
        basePrompt = SkillLoader.loadContent("/skills/KAFKA_METRICS_INSPECTOR.md");
        // Until applySharedGuidance runs (after every Specialist bean exists — see the app
        // module's injector), fall back to the base prompt alone so systemPrompt is never null.
        systemPrompt = basePrompt;
    }

    @Override
    public void applySharedGuidance(String sharedGuidance) {
        systemPrompt = basePrompt + "\n\n" + sharedGuidance;
    }

    @AchievesGoal(description = "Kafka metrics question answered")
    @Action(description = """
            Answer a historical time-series question about Kafka using VictoriaMetrics.
            Covers: consumer lag trends, broker throughput, partition health over time,
            capacity analysis, and metric comparisons across a time window.
            The answer is derived from time-series data, not live broker state.
            """)
    public MetricsAnswer answer(MetricsQuestion question, Ai ai) {
        if (!mcpHealthChecker.isAvailable()) {
            return new MetricsAnswer(mcpHealthChecker.unavailableMessage());
        }
        UnfoldingTool vmTools = mcpClientProvider.unfoldingWithRetry(
                "vm-metrics",
                "Invoke this to reveal the VictoriaMetrics query tools (metrics, query, query_range, "
                        + "labels, label_values) for Kafka time-series data. You MUST call this before "
                        + "you can query metrics.",
                VmMcpClientProvider.standardChildUsageNotes("vm-metrics",
                        "Start with `metrics` (discovery, at most once) using the narrowest pattern for "
                                + "the question, then `query` (instant, default) or `query_range` (only for "
                                + "an explicit trend/history request), per the workflow in your system prompt.")
        );
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
                ai.withAutoLlm()
                        .withSystemPrompt(systemPrompt)
                        .withTool(vmTools)
                        .withToolObject(metricsSkillTools)
                        .withToolCallInspectors(ToolCallAuditLogger.INSTANCE)
                        .withGuardRails(NoSecretLeakageGuardRail.INSTANCE)
                        .createObject(question.text(), String.class));
        return new MetricsAnswer(response);
    }

    @Override
    public String name() {
        return KafkaMetricsInspector.class.getSimpleName();
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
