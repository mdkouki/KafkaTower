package com.lynra.kafkatower.agents.specialists.log;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.SpecialReturnException;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.common.Ai;
import com.lynra.kafkatower.agents.core.audit.LlmCallTimer;
import com.lynra.kafkatower.agents.core.audit.ToolCallAuditLogger;
import com.lynra.kafkatower.agents.core.specialist.Specialist;
import com.lynra.kafkatower.agents.specialists.config.OpenSearchClientProvider;
import com.lynra.kafkatower.agents.specialists.config.OpenSearchClientRegistry;
import com.lynra.kafkatower.agents.core.guardrails.NoSecretLeakageGuardRail;
import com.lynra.kafkatower.tools.OpenSearchQueryTools;
import com.lynra.kafkatower.utils.SkillLoader;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.Optional;
import java.util.Set;

/**
 * Queries OpenSearch logs for anomalies and distributed traces. Each Kafka cluster has its
 * own OpenSearch connection (see {@link OpenSearchClientRegistry}), so the target cluster is
 * resolved from the question before the query tools are invoked. Talks to OpenSearch's REST
 * API directly with HTTP basic auth ({@link OpenSearchQueryTools}) rather than via MCP — the
 * OpenSearch version deployed here does not support the native MCP server.
 * Disabled by default — activate by setting agent.mcp.os.enabled=true and at least one
 * entry under agent.mcp.os.clusters.
 */
// @ConditionalOnBean is only well-defined on auto-configuration classes; on a regular scanned
// @Component like OpenSearchClientRegistry its evaluation order relative to registry
// registration is undefined, so this agent's own presence could vary by classpath scan order.
// The property condition is deterministic and matches what the registry itself is keyed on.
@ConditionalOnProperty(prefix = "agent.mcp.os", name = "enabled", havingValue = "true")
@Agent(description = "Queries OpenSearch logs for anomalies, exceptions, and distributed traces. " +
        "Use this agent when the question requires log correlation or trace analysis.")
public class KafkaLogInspector implements Specialist<LogQuestion, LogAnswer> {

    private static final Logger log = LoggerFactory.getLogger(KafkaLogInspector.class);

    private final OpenSearchClientRegistry registry;
    private final OpenSearchQueryTools queryTools;
    private final long slowCallThresholdMs;
    private String basePrompt;
    private String systemPrompt;

    public KafkaLogInspector(OpenSearchClientRegistry registry, OpenSearchQueryTools queryTools,
                              @Value("${agent.llm.slow-call-warning-ms:15000}") long slowCallThresholdMs) {
        this.registry = registry;
        this.queryTools = queryTools;
        this.slowCallThresholdMs = slowCallThresholdMs;
    }

    @PostConstruct
    void loadSkill() {
        // Cross-specialist shared guidance (tool-output handling, query strategy, reply format)
        // is NOT loaded here — see applySharedGuidance below.
        basePrompt = SkillLoader.loadContent("/skills/KAFKA_LOG_INSPECTOR.md");
        // Until applySharedGuidance runs (after every Specialist bean exists — see the app
        // module's injector), fall back to the base prompt alone so systemPrompt is never null.
        systemPrompt = basePrompt;
    }

    @Override
    public void applySharedGuidance(String sharedGuidance) {
        systemPrompt = basePrompt + "\n\n" + sharedGuidance;
    }

    /**
     * Resolves the target OpenSearch cluster before any log query is issued. The two
     * unavailability checks that used to live at the top of {@code answer()} are handled here
     * via {@link SpecialReturnException}: throwing one with {@code type = LogAnswer.class} makes
     * the framework place that {@link LogAnswer} directly on the blackboard, which already
     * satisfies {@link #searchLogs}'s {@code @AchievesGoal} — so the plan ends here instead of
     * proceeding to {@code searchLogs}, without a second real LLM call ever running.
     */
    @Action(description = "Resolve which OpenSearch-backed Kafka cluster a log/trace question is about.")
    public ResolvedCluster resolveLogCluster(LogQuestion question, Ai ai) {
        log.info("Log inspection request: \"{}\"", question.text());

        if (registry.isEmpty()) {
            log.warn("Log inspection unavailable: no OpenSearch clusters configured (agent.mcp.os.clusters is empty).");
            throw unavailable("no OpenSearch clusters configured",
                    "Log inspection is not available: no OpenSearch clusters are configured (agent.mcp.os.clusters).");
        }

        ResolvedCluster resolution = resolveCluster(question.text(), ai);
        OpenSearchClientProvider provider = resolution.provider();
        log.info("Log inspection resolved to cluster '{}' (baseUrl={})", provider.clusterName(), provider.baseUrl());

        if (!provider.isAvailable()) {
            log.warn("Log inspection aborted: OpenSearch for cluster '{}' failed its health check " +
                    "(baseUrl={}) — see the preceding OpenSearchClientProvider warning for the cause.",
                    provider.clusterName(), provider.baseUrl());
            throw unavailable("OpenSearch cluster unreachable",
                    "The OpenSearch log service for cluster '" + provider.clusterName() + "' is currently unreachable.");
        }

        return resolution;
    }

    private static SpecialReturnException unavailable(String reason, String message) {
        return new SpecialReturnException(reason, LogAnswer.class) {
            @Override
            public Object handle(ActionContext context) {
                return new LogAnswer(message);
            }
        };
    }

    @AchievesGoal(description = "Log inspection question answered")
    @Action(description = """
            Query OpenSearch logs for anomalies, exceptions, or distributed traces
            related to Kafka services. Use when the question involves log correlation
            or tracing rather than metrics or registry data.
            """)
    public LogAnswer searchLogs(LogQuestion question, ResolvedCluster resolution, Ai ai) {
        OpenSearchClientProvider provider = resolution.provider();

        // Non-streaming on purpose — see KafkaMetricsInspector.answer for why: the streaming
        // call path (StreamingLlmOperationsImpl) resolves its tool list once via
        // ToolResolutionHelper and hands it to the raw LLM stream, never re-evaluating the
        // tool loop between rounds the way DefaultToolLoop does over createObject. This
        // specialist's own token stream is suppressed by the caller anyway
        // (SubAgentTools.run), so streaming bought nothing here and risked the LLM never
        // actually reaching search_documents/aggregate_documents/get_document.
        String response = LlmCallTimer.timed(log, "log answer", slowCallThresholdMs, () ->
                ai.withAutoLlm()
                        .withSystemPrompt(systemPrompt + "\n\nYou are querying cluster '" + provider.clusterName() + "'.")
                        .withToolObject(queryTools)
                        .withToolCallInspectors(ToolCallAuditLogger.INSTANCE)
                        .withGuardRails(NoSecretLeakageGuardRail.INSTANCE)
                        .createObject(question.text(), String.class));
        log.info("Log inspection completed for cluster '{}'", provider.clusterName());
        String prefix = resolution.note() != null ? "(" + resolution.note() + ")\n\n" : "";
        return new LogAnswer(prefix + response);
    }

    /** Precondition: registry has at least one configured cluster (checked by the caller). */
    private ResolvedCluster resolveCluster(String questionText, Ai ai) {
        Set<String> clusters = registry.clusterNames();
        if (clusters.size() == 1) {
            String only = clusters.iterator().next();
            log.debug("Only one OpenSearch cluster configured ('{}') — using it without cluster selection.", only);
            return new ResolvedCluster(registry.forCluster(only).orElseThrow(), null);
        }

        String defaultHint = registry.defaultCluster()
                .map(c -> "If the question does not name a cluster, assume '" + c + "'.")
                .orElse("If the question does not name a cluster, pick the most likely one from context; " +
                        "if truly ambiguous, return the first cluster alphabetically.");

        String prompt = """
                Identify which Kafka cluster this log/trace question is about.
                Valid clusters: %s
                %s
                Respond with exactly one of the valid cluster names.
                """.formatted(String.join(", ", clusters), defaultHint);

        ClusterSelection selection = LlmCallTimer.timed(log, "cluster selection", slowCallThresholdMs, () ->
                ai.withAutoLlm()
                        .withSystemPrompt(prompt)
                        .createObject(questionText, ClusterSelection.class));
        log.debug("Cluster selection for log inspection: LLM picked '{}' from candidates {}",
                selection.clusterName(), clusters);

        Optional<OpenSearchClientProvider> resolved = registry.forCluster(selection.clusterName());
        if (resolved.isEmpty()) {
            String fallback = clusters.iterator().next();
            log.warn("Cluster selection picked '{}', which has no matching OpenSearch connection " +
                            "among configured clusters {} — falling back to '{}'. This usually means the " +
                            "configured agent.mcp.os.clusters key doesn't match the cluster name the user used.",
                    selection.clusterName(), clusters, fallback);
            String note = "cluster '" + selection.clusterName() + "' is not configured for logs; searched '"
                    + fallback + "' instead";
            return new ResolvedCluster(registry.forCluster(fallback).orElseThrow(), note);
        }
        return new ResolvedCluster(resolved.get(), null);
    }

    @Override
    public String name() {
        return KafkaLogInspector.class.getSimpleName();
    }

    @Override
    public Class<LogAnswer> answerType() {
        return LogAnswer.class;
    }

    @Override
    public LogQuestion question(String text) {
        return new LogQuestion(text);
    }

    @Override
    public String text(LogAnswer answer) {
        return answer.text();
    }
}
