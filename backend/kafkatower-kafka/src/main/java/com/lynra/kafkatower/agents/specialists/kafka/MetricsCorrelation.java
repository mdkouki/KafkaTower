package com.lynra.kafkatower.agents.specialists.kafka;

/**
 * Result of correlating {@link AdminSnapshot} findings with metric trends (and, where
 * the Entity-Scale Discipline narrowing pattern applies, any follow-up AdminClient calls made
 * against the worst-N entities metrics surfaced). Fed to {@code synthesizeInvestigation} as the
 * second and final piece of evidence.
 */
public record MetricsCorrelation(String summary) {

    static MetricsCorrelation notNeeded() {
        return new MetricsCorrelation("Not run: the admin findings fully answer the question, no metric history is needed.");
    }

    static MetricsCorrelation unavailable(String reason) {
        return new MetricsCorrelation("Not run: " + reason + " Metric history could not be checked.");
    }
}
