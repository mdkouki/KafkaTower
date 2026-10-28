package com.lynra.kafkatower.agents.specialists.config;

import com.embabel.agent.api.tool.progressive.UnfoldingTool;

/**
 * The time-series store behind the metrics tools. Specialists depend only on this interface:
 * they never learn which PromQL-compatible store (VictoriaMetrics, Prometheus, ...) answers.
 * <p>
 * The LLM sees the same tool surface — {@code metrics}, {@code query}, {@code query_range},
 * {@code labels}, {@code label_values} — so the skills and playbooks are written once.
 */
public interface MetricsBackend {

    /** True if the store is reachable and ready to answer queries. */
    boolean isAvailable();

    /** Backend-neutral text for the LLM and the user when {@link #isAvailable()} is false. */
    String unavailableMessage();

    /**
     * The query tools behind an {@link UnfoldingTool} facade the LLM must invoke before it can
     * query. {@code purpose} completes "…for <purpose>" in the facade description;
     * {@code workflowClause} says which tool to call first and why.
     */
    UnfoldingTool queryTools(String facadeName, String purpose, String workflowClause);

    default String facadeDescription(String purpose) {
        return "Invoke this to reveal the metrics query tools (metrics, query, query_range, labels, "
                + "label_values) for " + purpose + ". You MUST call this before you can query metrics.";
    }

    /**
     * Shared skeleton for a facade's {@code childToolUsageNotes}: the "call the real tool
     * directly, don't re-invoke the facade" instruction and the {@code cluster="<name>"}
     * requirement are identical for every backend — only {@code workflowClause} varies.
     */
    static String standardChildUsageNotes(String facadeName, String workflowClause) {
        return "Call one of the revealed tools directly by name — do NOT call " + facadeName
                + " again. " + workflowClause + " Every query MUST include cluster=\"<name>\".";
    }
}
