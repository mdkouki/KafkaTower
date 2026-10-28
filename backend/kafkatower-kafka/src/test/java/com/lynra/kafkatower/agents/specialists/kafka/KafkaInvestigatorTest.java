package com.lynra.kafkatower.agents.specialists.kafka;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaInvestigatorTest {

    @Test
    void withMetricsUnavailableNoteAppendsMessageAndInstructionToTheBasePrompt() {
        String enriched = KafkaInvestigator.withMetricsUnavailableNote("base prompt", "The metrics service is down");

        assertThat(enriched)
                .startsWith("base prompt")
                .contains("The metrics service is down")
                .contains("Do not attempt to query metrics");
    }

    @Test
    void withMetricsUnavailableNotePreservesTheBasePromptUnchangedAsAPrefix() {
        String base = "You are running the SECOND step of a two-step investigation...";

        String enriched = KafkaInvestigator.withMetricsUnavailableNote(base, "unavailable");

        assertThat(enriched).startsWith(base);
    }
}
