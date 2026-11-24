package com.lynra.kafkatower.agents.specialists.registry;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.Ai;
import com.lynra.kafkatower.agents.core.audit.LlmCallTimer;
import com.lynra.kafkatower.agents.core.audit.ToolCallAuditLogger;
import com.lynra.kafkatower.agents.core.guardrails.NoSecretLeakageGuardRail;
import com.lynra.kafkatower.agents.core.specialist.Specialist;
import com.lynra.kafkatower.config.ConditionalOnAssistantEnabled;
import com.lynra.kafkatower.collections.FullListEnforcer;
import com.lynra.kafkatower.collections.UnreadCollectionTracker;
import com.lynra.kafkatower.tools.BulkCollectionTools;
import com.lynra.kafkatower.tools.RegistryQueryTools;
import com.lynra.kafkatower.utils.SkillLoader;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

/**
 * Answers dependency and usage questions from the registry — the in-memory topic and ACL graphs
 * built per cluster — without touching the brokers except for the optional live consumer-group
 * lookup in {@link RegistryQueryTools#topicConsumerGroups}.
 */
@ConditionalOnAssistantEnabled
@Agent(description = "Queries the registry for dependencies between Kafka resources and who uses what: " +
        "which principals/services produce to or consume from a topic, what a principal can access, " +
        "and which services depend on which others through shared topics. Use this agent for " +
        "ownership, usage, impact-analysis and 'who depends on X' questions. Answers come from ACL grants " +
        "(who is allowed) plus optional live consumer-group lag, not from who is currently active: pair it " +
        "with the investigation agent when live consumption matters. Not for metrics or logs. Name the " +
        "cluster and the topic/service/principal.")
public class KafkaRegistryInspector implements Specialist<RegistryQuestion, RegistryAnswer> {

    private static final Logger log = LoggerFactory.getLogger(KafkaRegistryInspector.class);

    private final RegistryQueryTools queryTools;
    private final BulkCollectionTools bulkCollectionTools;
    private final long slowCallThresholdMs;
    private String basePrompt;
    private volatile String sharedGuidance;

    public KafkaRegistryInspector(RegistryQueryTools queryTools,
                                  BulkCollectionTools bulkCollectionTools,
                                  @Value("${agent.llm.slow-call-warning-ms:15000}") long slowCallThresholdMs) {
        this.queryTools = queryTools;
        this.bulkCollectionTools = bulkCollectionTools;
        this.slowCallThresholdMs = slowCallThresholdMs;
    }

    @PostConstruct
    void loadSkill() {
        basePrompt = SkillLoader.loadContent("/skills/KAFKA_REGISTRY_INSPECTOR.md");
    }

    @Override
    public void applySharedGuidance(String sharedGuidance) {
        this.sharedGuidance = sharedGuidance;
    }

    private String systemPrompt() {
        String guidance = sharedGuidance;
        return guidance == null ? basePrompt : basePrompt + "\n\n" + guidance;
    }

    @AchievesGoal(description = "Registry dependency/usage question answered")
    @Action(description = """
            Query the registry for who produces to or consumes from a topic, what a principal can
            access, and which services depend on each other through shared topics. Use for
            ownership, usage and impact questions — not for metrics, logs or live broker health.
            """)
    public RegistryAnswer answer(RegistryQuestion question, Ai ai) {
        log.info("Registry inspection request: \"{}\"", question.text());
        // Non-streaming on purpose, same reasoning as KafkaLogInspector.searchLogs.
        UnreadCollectionTracker unreadCollections = new UnreadCollectionTracker();
        String response = LlmCallTimer.timed(log, "registry answer", slowCallThresholdMs, () ->
                FullListEnforcer.answer(ai.withAutoLlm()
                        .withSystemPrompt(systemPrompt())
                        .withToolObject(queryTools)
                        .withToolObject(bulkCollectionTools)
                        .withToolCallInspectors(ToolCallAuditLogger.INSTANCE, unreadCollections)
                        .withGuardRails(NoSecretLeakageGuardRail.INSTANCE),
                        question.text(), unreadCollections));
        return new RegistryAnswer(response);
    }

    @Override
    public String name() {
        return KafkaRegistryInspector.class.getSimpleName();
    }

    @Override
    public String toolName() {
        return "askRegistry";
    }

    @Override
    public Class<RegistryAnswer> answerType() {
        return RegistryAnswer.class;
    }

    @Override
    public RegistryQuestion question(String text) {
        return new RegistryQuestion(text);
    }

    @Override
    public String text(RegistryAnswer answer) {
        return answer.text();
    }
}
