package com.lynra.kafkatower.agents.specialists.kafka;

import com.lynra.kafkatower.collections.LargeResultGuard;
import com.lynra.kafkatower.config.ConditionalOnAssistantEnabled;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.tool.progressive.UnfoldingTool;
import com.lynra.kafkatower.agents.core.audit.LlmCallTimer;
import com.lynra.kafkatower.agents.core.audit.ToolCallAuditLogger;
import com.lynra.kafkatower.agents.core.specialist.Specialist;
import com.lynra.kafkatower.agents.specialists.config.MetricsBackend;
import com.lynra.kafkatower.agents.core.guardrails.NoSecretLeakageGuardRail;
import com.lynra.kafkatower.tools.KafkaAdminClientTools;
import com.lynra.kafkatower.tools.MetricsSkills;
import com.lynra.kafkatower.utils.AgentSkills;
import com.lynra.kafkatower.utils.CachedTools;
import com.lynra.kafkatower.utils.ToolCallTextDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.util.function.UnaryOperator;

@ConditionalOnAssistantEnabled
@Agent(description = "Handles live Kafka state investigation and active troubleshooting: " +
        "consumer group health, partition assignments, active lag spikes, and root-cause analysis. " +
        "Use this agent when the answer requires AdminClient or correlating live state with metrics, " +
        "when there is an issue to investigate (a suspected or confirmed problem, an anomaly, or an " +
        "unexplained/contradictory result from another source), or when the user explicitly asks to " +
        "investigate, troubleshoot or find a root cause. A plain status question with no such signal " +
        "doesn't qualify on its own: check the metrics agent first, and investigate only if what comes " +
        "back is incomplete or doesn't add up. It discovers and queries metrics itself, so name the " +
        "specific gap to close. Gets priority over KafkaMetricsInspector when a question spans both " +
        "metrics and live state.")
public class KafkaInvestigator implements Specialist<InvestigationQuestion, InvestigationAnswer> {

    private static final Logger log = LoggerFactory.getLogger(KafkaInvestigator.class);
    static final String INVESTIGATOR_SKILL = "kafka-investigator";
    static final String ADMIN_REFERENCE_SKILL = "kafka-admin-reference";
    static final String REPORT_SKILL = "kafka-investigation-report";
    private static final String TOOL_CALL_RETRY_INSTRUCTION = """


            Your previous reply was a tool call written out as text. No tool exists in this step and
            nothing will run it. Reply with the finished Kafka Investigation Report in plain markdown
            only: no JSON, no tool calls, no tool names as instructions.
            """;

    private final KafkaAdminClientTools kafkaAdminClientTools;
    private final AgentSkills agentSkills;
    private final CachedTools cachedTools;
    private final LargeResultGuard largeResultGuard;
    private final MetricsBackend metricsBackend;
    private final long slowCallThresholdMs;
    private volatile String sharedGuidance;

    public KafkaInvestigator(KafkaAdminClientTools kafkaAdminClientTools,
                             AgentSkills agentSkills,
                             CachedTools cachedTools,
                             LargeResultGuard largeResultGuard,
                             MetricsBackend metricsBackend,
                             @Value("${agent.llm.slow-call-warning-ms:15000}") long slowCallThresholdMs) {
        this.kafkaAdminClientTools = kafkaAdminClientTools;
        this.agentSkills = agentSkills;
        this.cachedTools = cachedTools;
        this.largeResultGuard = largeResultGuard;
        this.metricsBackend = metricsBackend;
        this.slowCallThresholdMs = slowCallThresholdMs;
    }

    @Override
    public void applySharedGuidance(String sharedGuidance) {
        this.sharedGuidance = sharedGuidance;
    }

    private String systemPrompt() {
        return agentSkills.systemPrompt(INVESTIGATOR_SKILL, sharedGuidance);
    }

    private String synthesisSystemPrompt() {
        return agentSkills.systemPrompt(REPORT_SKILL, sharedGuidance);
    }

    /**
     * The planner can't schedule {@link #correlateMetrics} before this produces an
     * {@link AdminSnapshot}, making "Admin First, Metrics Second" (kafka-investigator skill) a
     * structural guarantee rather than a prose rule.
     */
    @Action(description = """
            Gather current Kafka ground truth via AdminClient tools: consumer group state,
            partition assignments, broker state, ACLs, quotas. Always runs first — the
            structural precondition for correlateMetrics.
            """)
    public AdminSnapshot gatherAdminState(InvestigationQuestion question, Ai ai) {
        String prompt = systemPrompt() + "\n\n" + """
                You are running the FIRST step of a two-step investigation: gather current
                ground truth using ONLY the Kafka Admin Client tools below (and the skill tools that
                read reference material). Do not speculate
                about metrics or historical trends here — that happens in the next step.
                Produce a faithful, complete summary of every relevant admin finding (do not
                omit findings for brevity) — this summary is the ONLY record the next step will
                have of what you found; nothing else about this step carries forward.

                End with exactly one last line. `%1$s yes` if answering the question needs metric
                history (trends, rates, growth, when something started) or narrowing by metrics.
                `%1$s no` if the admin findings fully answer it (current state, counts, lists,
                configs, ACLs, quotas).
                """.formatted(AdminSnapshot.VERDICT_LABEL);
        String response = LlmCallTimer.timed(log, "investigation admin state", slowCallThresholdMs, () ->
                largeResultGuard.answer(ai.withAutoLlm()
                        .withSystemPrompt(prompt)
                        .withReference(agentSkills.reference(MetricsSkills.INVESTIGATION_PLAYBOOKS, ADMIN_REFERENCE_SKILL))
                        .withTools(cachedTools.from(kafkaAdminClientTools))
                        .withToolCallInspectors(ToolCallAuditLogger.INSTANCE)
                        .withGuardRails(NoSecretLeakageGuardRail.INSTANCE),
                        question.text()));
        return AdminSnapshot.fromResponse(response);
    }

    /**
     * Re-includes {@code kafkaAdminClientTools} alongside the metrics tools so the Entity-Scale
     * Discipline narrowing pattern (metrics {@code topk} to find the worst-N, then
     * {@code describeConsumerGroup}/{@code listConsumerGroupOffsets} on just those — see
     * the kafka-investigator skill) fits in one action instead of needing a separate re-entry action.
     */
    @Action(description = """
            Correlate the AdminSnapshot findings against metric trends: rates, growth,
            anomaly timing. May re-enter AdminClient tools to narrow into the worst-N entities
            metrics surface (Entity-Scale Discipline). Runs after gatherAdminState.
            """)
    public MetricsCorrelation correlateMetrics(InvestigationQuestion question, AdminSnapshot adminSnapshot, Ai ai) {
        if (!adminSnapshot.metricsNeeded()) {
            return MetricsCorrelation.notNeeded();
        }
        if (!metricsBackend.isAvailable()) {
            return MetricsCorrelation.unavailable(metricsBackend.unavailableMessage());
        }
        String base = systemPrompt() + "\n\n" + """
                You are running the SECOND step of a two-step investigation. Here is the admin
                ground truth already gathered in step one — treat it as established fact, do not
                re-fetch it:

                ## Admin Snapshot (from step one)
                %s

                Your job now: decide what historical/metric context is actually needed given the
                admin findings above, confirm metric names in the kafka-metrics-catalog skill before querying,
                and query only what answers the questions the admin findings raised. You may call
                AdminClient tools again ONLY to narrow into specific entities metrics surface
                (e.g. topk lag → describeConsumerGroup on just those groups) — do not redo the
                broad admin discovery from step one.
                """.formatted(adminSnapshot.summary());

        PromptRunner runner = correlationRunner(ai, base);
        // Non-streaming on purpose — see KafkaMetricsInspector.answer for why: the streaming
        // call path never re-evaluates ToolInjectionStrategy between rounds, so UnfoldingTool's
        // facade-then-real-tools swap can't work over it. This specialist's own token stream is
        // suppressed by the caller anyway (SubAgentTools.run), so streaming bought nothing here.
        String response = LlmCallTimer.timed(log, "investigation metrics correlation", slowCallThresholdMs, () ->
                largeResultGuard.answer(runner
                        .withToolCallInspectors(ToolCallAuditLogger.INSTANCE)
                        .withGuardRails(NoSecretLeakageGuardRail.INSTANCE),
                        question.text()));
        return new MetricsCorrelation(response);
    }

    private PromptRunner correlationRunner(Ai ai, String base) {
        UnfoldingTool metricsTools = metricsBackend.queryTools(
                "metrics-investigation",
                "correlating live Kafka state with metric trends",
                "Use `metrics` for discovery, then `query` (instant) or `query_range` (trend) "
                        + "to correlate against the admin snapshot above.");
        return ai.withAutoLlm()
                .withSystemPrompt(base)
                .withReference(agentSkills.reference(MetricsSkills.INVESTIGATION_PLAYBOOKS, MetricsSkills.CATALOG,
                        ADMIN_REFERENCE_SKILL))
                .withTools(cachedTools.from(kafkaAdminClientTools))
                .withTool(metricsTools);
    }

    @AchievesGoal(description = "Full issue analysis and root cause found")
    @Action(description = """
            Synthesize the admin snapshot and metrics correlation into the final Kafka
            Investigation Report. Runs last, after gatherAdminState and correlateMetrics.
            """)
    public InvestigationAnswer synthesizeInvestigation(InvestigationQuestion question, AdminSnapshot adminSnapshot,
                                                         MetricsCorrelation metricsCorrelation, Ai ai) {
        String prompt = synthesisSystemPrompt() + "\n\n" + """
                You are running the THIRD and FINAL step. You have no tools in this step: do not call or
                describe any tool. Write the Kafka Investigation Report
                using only the evidence below — do not invent findings not present in either
                section. Follow the Output Format in the instructions above exactly.

                ## Admin Snapshot
                %s

                ## Metrics Correlation
                %s
                """.formatted(adminSnapshot.summary(), metricsCorrelation.summary());
        String response = synthesizeValidated(prompt, adminSnapshot, metricsCorrelation, promptText ->
                LlmCallTimer.timed(log, "investigation synthesis", slowCallThresholdMs, () ->
                        ai.withAutoLlm()
                                .withSystemPrompt(promptText)
                                .withToolCallInspectors(ToolCallAuditLogger.INSTANCE)
                                .withGuardRails(NoSecretLeakageGuardRail.INSTANCE)
                                .createObject(question.text(), String.class)));
        return new InvestigationAnswer(response);
    }

    /**
     * A tool-less step that answers with a tool call written out as text is retried once with the
     * mistake named; if it repeats, the gathered findings are returned unedited. A tool call must
     * never travel upstream as the specialist's answer: the orchestrator then writes one from
     * nothing.
     */
    String synthesizeValidated(String prompt, AdminSnapshot adminSnapshot, MetricsCorrelation metricsCorrelation,
                               UnaryOperator<String> llm) {
        String response = llm.apply(prompt);
        if (!ToolCallTextDetector.containsToolCall(response)) {
            return response;
        }
        log.warn("Investigation synthesis answered with a tool call instead of a report; retrying once");
        response = llm.apply(prompt + TOOL_CALL_RETRY_INSTRUCTION);
        if (!ToolCallTextDetector.containsToolCall(response)) {
            return response;
        }
        log.warn("Investigation synthesis answered with a tool call again; returning the gathered findings unedited");
        return findingsReport(adminSnapshot, metricsCorrelation);
    }

    private static String findingsReport(AdminSnapshot adminSnapshot, MetricsCorrelation metricsCorrelation) {
        return """
                ## Kafka Investigation Report

                The final write-up could not be produced, so these are the findings the investigation gathered, unedited.

                ### Admin findings
                %s

                ### Metrics correlation
                %s
                """.formatted(adminSnapshot.summary(), metricsCorrelation.summary());
    }

    @Override
    public String name() {
        return KafkaInvestigator.class.getSimpleName();
    }

    @Override
    public String toolName() {
        return "askInvestigation";
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
