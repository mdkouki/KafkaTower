package com.lynra.kafkatower.agents.specialists.kafka;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaInvestigatorTest {

    @Test
    void withMcpUnavailableNoteAppendsMessageAndInstructionToTheBasePrompt() {
        String enriched = KafkaInvestigator.withMcpUnavailableNote("base prompt", "VictoriaMetrics MCP is down");

        assertThat(enriched)
                .startsWith("base prompt")
                .contains("VictoriaMetrics MCP is down")
                .contains("Do not attempt to query metrics");
    }

    @Test
    void withMcpUnavailableNotePreservesTheBasePromptUnchangedAsAPrefix() {
        String base = "You are running the SECOND step of a two-step investigation...";

        String enriched = KafkaInvestigator.withMcpUnavailableNote(base, "unavailable");

        assertThat(enriched).startsWith(base);
    }
}
