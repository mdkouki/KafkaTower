package com.lynra.kafkatower.agents.core.root;

/** LLM-generated classification of a user message, recorded for analytics only. */
public record ClassificationResult(
        String theme,
        String keywords,
        String targetAgent
) {}
