package com.lynra.kafkatower.agents.specialists.kafka;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Faithful summary of {@code gatherAdminState}'s AdminClient findings — the only view
 * {@code correlateMetrics} and {@code synthesizeInvestigation} get of the admin step, since
 * actions share no LLM conversation/session (each is a fresh call). Must not be lossy: dropping
 * a finding here means it's gone for the rest of the investigation.
 * <p>
 * {@code metricsNeeded} is the admin step's own verdict on whether metric history is required to
 * answer. It defaults to {@code true}: a missing or malformed verdict from the LLM must never skip
 * the metrics step.
 */
public record AdminSnapshot(String summary, boolean metricsNeeded) {

    static final String VERDICT_LABEL = "METRICS_NEEDED:";

    private static final Pattern VERDICT_LINE =
            Pattern.compile("(?im)^[ \\t]*" + VERDICT_LABEL + "[ \\t]*(yes|no)[ \\t]*$");

    static AdminSnapshot fromResponse(String response) {
        Matcher verdict = VERDICT_LINE.matcher(response);
        boolean metricsNeeded = true;
        while (verdict.find()) {
            metricsNeeded = verdict.group(1).equalsIgnoreCase("yes");
        }
        return new AdminSnapshot(VERDICT_LINE.matcher(response).replaceAll("").strip(), metricsNeeded);
    }
}
