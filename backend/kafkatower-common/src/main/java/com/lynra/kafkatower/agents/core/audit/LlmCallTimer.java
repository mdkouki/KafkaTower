package com.lynra.kafkatower.agents.core.audit;

import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * Times a single {@code ai.withAutoLlm()...createObject(...)} call and warns if it's slower
 * than the given threshold. Each call site passes its own logger and a short label identifying
 * which LLM call it is (e.g. "classification", "draft round 2") so the warning is traceable
 * back to a specific step.
 */
public final class LlmCallTimer {

    private LlmCallTimer() {
    }

    public static <T> T timed(Logger log, String callLabel, long slowThresholdMs, Supplier<T> call) {
        long start = System.currentTimeMillis();
        try {
            return call.get();
        } finally {
            long durationMs = System.currentTimeMillis() - start;
            if (durationMs >= slowThresholdMs) {
                log.warn("Slow LLM call — {} took {}ms (threshold {}ms)", callLabel, durationMs, slowThresholdMs);
            }
        }
    }
}
