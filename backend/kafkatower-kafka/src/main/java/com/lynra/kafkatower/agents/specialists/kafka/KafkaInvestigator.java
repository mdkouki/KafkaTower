package com.lynra.kafkatower.agents.specialists.kafka;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.tool.progressive.UnfoldingTool;
import com.lynra.kafkatower.agents.core.audit.LlmCallTimer;
import com.lynra.kafkatower.agents.core.audit.ToolCallAuditLogger;
import com.lynra.kafkatower.agents.core.specialist.Specialist;
import com.lynra.kafkatower.agents.specialists.config.McpHealthChecker;
import com.lynra.kafkatower.agents.specialists.config.VmMcpClientProvider;
import com.lynra.kafkatower.agents.core.guardrails.NoSecretLeakageGuardRail;
import com.lynra.kafkatower.tools.KafkaAdminClientTools;
import com.lynra.kafkatower.tools.MetricsSkillTools;
import com.lynra.kafkatower.utils.SkillLoader;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

@Agent(description = "Handles live Kafka state investigation and active troubleshooting: " +
        "consumer group health, partition assignments, active lag spikes, and root-cause analysis. " +
        "Use this agent when the answer requires AdminClient or correlating live state with metrics. " +
        "Gets priority over KafkaMetricsInspector when a question spans both metrics and live state.")
public class KafkaInvestigator implements Specialist<InvestigationQuestion, InvestigationAnswer> {

    private static final Logger log = LoggerFactory.getLogger(KafkaInvestigator.class);

    private final KafkaAdminClientTools kafkaAdminClientTools;
    private final MetricsSkillTools metricsSkillTools;
    private final VmMcpClientProvider mcpClientProvider;
    private final McpHealthChecker mcpHealthChecker;
    private final long slowCallThresholdMs;
    private String basePrompt;
    private String systemPrompt;

    public KafkaInvestigator(KafkaAdminClientTools kafkaAdminClientTools,
                             MetricsSkillTools metricsSkillTools,
                             VmMcpClientProvider mcpClientProvider,
                             McpHealthChecker mcpHealthChecker,
                             @Value("${agent.llm.slow-call-warning-ms:15000}") long slowCallThresholdMs) {
        this.kafkaAdminClientTools = kafkaAdminClientTools;
        this.metricsSkillTools = metricsSkillTools;
        this.mcpClientProvider = mcpClientProvider;
        this.mcpHealthChecker = mcpHealthChecker;
        this.slowCallThresholdMs = slowCallThresholdMs;
    }

    @PostConstruct
    void loadSkill() {
        // Only the always-relevant material is loaded at startup: main skill (routes to the
        // right playbook scope) + admin commands (always available since this agent always has
        // AdminClient tools). The investigation playbooks and metrics catalog — ~10K tokens
        // combined, the bulk of what used to be concatenated here unconditionally — are now
        // fetched on demand via MetricsSkillTools, scoped to what the question actually needs.
        // Cross-specialist shared guidance (tool-output handling, query strategy, reply format)
        // is NOT loaded here — see applySharedGuidance below.
        basePrompt = SkillLoader.loadContent("/skills/kafka-investigation/KAFKA_INVESTIGATOR.md");
        // Until applySharedGuidance runs (after every Specialist bean exists — see the app
        // module's injector), fall back to the base prompt alone so systemPrompt is never null.
        systemPrompt = basePrompt;
    }

    @Override
    public void applySharedGuidance(String sharedGuidance) {
        systemPrompt = basePrompt
                + "\n\n" + sharedGuidance
                + "\n\n## Kafka Admin Commands Reference\n\n"
                + SkillLoader.loadContent("/skills/kafka-investigation/references/kafka-admin-commands.md");
    }

    /**
     * The planner can't schedule {@link #correlateMetrics} before this produces an
     * {@link AdminSnapshot}, making "Admin First, Metrics Second" (KAFKA_INVESTIGATOR.md) a
     * structural guarantee rather than a prose rule.
     */
    @Action(description = """
            Gather current Kafka ground truth via AdminClient tools: consumer group state,
            partition assignments, broker state, ACLs, quotas. Always runs first — the
            structural precondition for correlateMetrics.
            """)
    public AdminSnapshot gatherAdminState(InvestigationQuestion question, Ai ai) {
        String prompt = systemPrompt + "\n\n" + """
                You are running the FIRST step of a two-step investigation: gather current
                ground truth using ONLY the Kafka Admin Client tools below. Do not speculate
                about metrics or historical trends here — that happens in the next step.
                Produce a faithful, complete summary of every relevant admin finding (do not
                omit findings for brevity) — this summary is the ONLY record the next step will
                have of what you found; nothing else about this step carries forward.
                """;
        String response = LlmCallTimer.timed(log, "investigation admin state", slowCallThresholdMs, () ->
                ai.withAutoLlm()
                        .withSystemPrompt(prompt)
                        .withToolObject(kafkaAdminClientTools)
                        .withToolCallInspectors(ToolCallAuditLogger.INSTANCE)
                        .withGuardRails(NoSecretLeakageGuardRail.INSTANCE)
                        .createObject(question.text(), String.class));
        return new AdminSnapshot(response);
    }

    /**
     * Re-includes {@code kafkaAdminClientTools} alongside the metrics tools so the Entity-Scale
     * Discipline narrowing pattern (metrics {@code topk} to find the worst-N, then
     * {@code describeConsumerGroup}/{@code listConsumerGroupOffsets} on just those — see
     * KAFKA_INVESTIGATOR.md) fits in one action instead of needing a separate re-entry action.
     */
    @Action(description = """
            Correlate the AdminSnapshot findings against VictoriaMetrics trends: rates, growth,
            anomaly timing. May re-enter AdminClient tools to narrow into the worst-N entities
            metrics surface (Entity-Scale Discipline). Runs after gatherAdminState.
            """)
    public MetricsCorrelation correlateMetrics(InvestigationQuestion question, AdminSnapshot adminSnapshot, Ai ai) {
        String base = systemPrompt + "\n\n" + """
                You are running the SECOND step of a two-step investigation. Here is the admin
                ground truth already gathered in step one — treat it as established fact, do not
                re-fetch it:

                ## Admin Snapshot (from step one)
                %s

                Your job now: decide what historical/metric context is actually needed given the
                admin findings above, confirm metric names via getMetricsCatalog before querying,
                and query only what answers the questions the admin findings raised. You may call
                AdminClient tools again ONLY to narrow into specific entities metrics surface
                (e.g. topk lag → describeConsumerGroup on just those groups) — do not redo the
                broad admin discovery from step one.
                """.formatted(adminSnapshot.summary());

        PromptRunner runner;
        if (mcpHealthChecker.isAvailable()) {
            UnfoldingTool vmTools = mcpClientProvider.unfoldingWithRetry(
                    "vm-investigation",
                    "Invoke this to reveal the VictoriaMetrics query tools (metrics, query, query_range, "
                            + "labels, label_values) for correlating live Kafka state with metric trends. "
                            + "You MUST call this before you can query metrics.",
                    VmMcpClientProvider.standardChildUsageNotes("vm-investigation",
                            "Use `metrics` for discovery, then `query` (instant) or `query_range` (trend) "
                                    + "to correlate against the admin snapshot above.")
            );
            runner = ai.withAutoLlm()
                    .withSystemPrompt(base)
                    .withToolObject(kafkaAdminClientTools)
                    .withToolObject(metricsSkillTools)
                    .withTool(vmTools);
        } else {
            // MCP down — still correlate using AdminClient-only re-entry, note the limitation
            runner = ai.withAutoLlm()
                    .withSystemPrompt(withMcpUnavailableNote(base, mcpHealthChecker.unavailableMessage()))
                    .withToolObject(kafkaAdminClientTools)
                    .withToolObject(metricsSkillTools);
        }
        // Non-streaming on purpose — see KafkaMetricsInspector.answer for why: the streaming
        // call path never re-evaluates ToolInjectionStrategy between rounds, so UnfoldingTool's
        // facade-then-real-tools swap can't work over it. This specialist's own token stream is
        // suppressed by the caller anyway (SubAgentTools.run), so streaming bought nothing here.
        String response = LlmCallTimer.timed(log, "investigation metrics correlation", slowCallThresholdMs, () ->
                runner
                        .withToolCallInspectors(ToolCallAuditLogger.INSTANCE)
                        .withGuardRails(NoSecretLeakageGuardRail.INSTANCE)
                        .createObject(question.text(), String.class));
        return new MetricsCorrelation(response);
    }

    /** Extracted from {@link #correlateMetrics}'s MCP-down branch for direct unit testing. */
    static String withMcpUnavailableNote(String base, String unavailableMessage) {
        return base + "\n\nNOTE: " + unavailableMessage +
                " Do not attempt to query metrics; note this limitation in your summary.";
    }

    /** No tools — pure synthesis of the two prior findings into KAFKA_INVESTIGATOR.md's report format. */
    @AchievesGoal(description = "Full issue analysis and root cause found")
    @Action(description = """
            Synthesize the admin snapshot and metrics correlation into the final Kafka
            Investigation Report. Runs last, after gatherAdminState and correlateMetrics.
            """)
    public InvestigationAnswer synthesizeInvestigation(InvestigationQuestion question, AdminSnapshot adminSnapshot,
                                                         MetricsCorrelation metricsCorrelation, Ai ai) {
        String prompt = systemPrompt + "\n\n" + """
                You are running the THIRD and FINAL step. Write the Kafka Investigation Report
                using only the evidence below — do not invent findings not present in either
                section. Follow the Output Format in the skill above exactly.

                ## Admin Snapshot
                %s

                ## Metrics Correlation
                %s
                """.formatted(adminSnapshot.summary(), metricsCorrelation.summary());
        String response = LlmCallTimer.timed(log, "investigation synthesis", slowCallThresholdMs, () ->
                ai.withAutoLlm()
                        .withSystemPrompt(prompt)
                        .withToolCallInspectors(ToolCallAuditLogger.INSTANCE)
                        .withGuardRails(NoSecretLeakageGuardRail.INSTANCE)
                        .createObject(question.text(), String.class));
        return new InvestigationAnswer(response);
    }

    @Override
    public String name() {
        return KafkaInvestigator.class.getSimpleName();
    }

    @Override
    public Class<InvestigationAnswer> answerType() {
        return InvestigationAnswer.class;
    }

    @Override
    public InvestigationQuestion question(String text) {
        return new InvestigationQuestion(text);
    }

    @Override
    public String text(InvestigationAnswer answer) {
        return answer.text();
    }
}
