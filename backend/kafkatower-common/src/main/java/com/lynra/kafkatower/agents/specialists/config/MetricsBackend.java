package com.lynra.kafkatower.agents.specialists.config;

import com.embabel.agent.api.tool.progressive.UnfoldingTool;

public interface MetricsBackend {

    boolean isAvailable();

    String unavailableMessage();

    UnfoldingTool queryTools(String facadeName, String purpose, String workflowClause);

    default String facadeDescription(String purpose) {
        return "Invoke this to reveal the metrics query tools (metrics, query, query_range, labels, "
                + "label_values) for " + purpose + ". You MUST call this before you can query metrics.";
    }

    static String standardChildUsageNotes(String facadeName, String workflowClause) {
        return "Call one of the revealed tools directly by name — do NOT call " + facadeName
                + " again. " + workflowClause + " Every query MUST include cluster=\"<name>\".";
    }
}
