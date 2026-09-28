package com.lynra.kafkatower.agents.core.root;

/**
 * LLM-judged check of a draft answer against the original user question AND the raw specialist
 * evidence gathered for it, run as a separate call from the orchestration itself — see
 * {@link KafkaInspector}. Covers two things: completeness ("does this answer actually satisfy
 * the question, or do I need another specialist") and faithfulness ("is every fact in the draft
 * actually traceable to the evidence, or did the synthesis step invent/alter something"). Making
 * this an explicit, code-enforced step means it happens even while the orchestrator is busy
 * calling tools and would otherwise skip self-checking.
 */
public record Completeness(
        boolean sufficient,
        String reason,
        /**
         * A short, stable slug naming the KIND of gap when {@code sufficient} is false (e.g.
         * "fabrication", "missing_information", "style") — see ROOT_COMPLETENESS_CHECK.md. Lets
         * {@link KafkaInspector} tell "the reviewer is repeating last round's complaint" from
         * "this is a new, different complaint" without fuzzy-matching the free-text {@code reason}
         * alone, which drifts in wording round to round even when the underlying gap hasn't
         * changed. Null/blank when {@code sufficient} is true.
         */
        String category
) {}
