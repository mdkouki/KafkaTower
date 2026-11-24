package com.lynra.kafkatower.agents.core.root;

/** LLM-judged completeness and faithfulness verdict on a draft answer; see {@link KafkaInspector}. */
public record Completeness(
        boolean sufficient,
        String reason,
        // Stable slug for the KIND of gap (see ROOT_COMPLETENESS_CHECK.md); KafkaInspector uses it because the
        // free-text reason drifts in wording between rounds. Null/blank when sufficient.
        String category
) {}
