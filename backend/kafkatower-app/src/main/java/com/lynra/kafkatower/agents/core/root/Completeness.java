package com.lynra.kafkatower.agents.core.root;

/** LLM-judged completeness and faithfulness verdict on a draft answer; see {@link KafkaInspector}. */
public record Completeness(
        boolean sufficient,
        String reason,
        // Stable slug for the KIND of gap (see ROOT_COMPLETENESS_CHECK.md); KafkaInspector uses it because the
        // free-text reason drifts in wording between rounds. Null/blank when sufficient.
        String category
) {

    static final String FABRICATION_CATEGORY = "fabrication";

    /** The draft states facts the specialists' results do not support. */
    boolean isFabrication() {
        return !sufficient && category != null && FABRICATION_CATEGORY.equalsIgnoreCase(category.trim());
    }
}
