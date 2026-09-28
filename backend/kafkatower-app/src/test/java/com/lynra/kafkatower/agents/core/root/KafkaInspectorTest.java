package com.lynra.kafkatower.agents.core.root;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaInspectorTest {

    private static final String FABRICATION = "fabrication";
    private static final String MISSING_INFO = "missing_information";

    @Test
    void isSameGapDetectsExactRepeat() {
        String gap = "Fabrication: the draft states 'one active broker (Broker 1)' as a fact not backed by evidence.";
        assertThat(KafkaInspector.isSameGap(FABRICATION, gap, FABRICATION, gap)).isTrue();
    }

    @Test
    void isSameGapDetectsNearIdenticalRewordingWhenCategoryMatches() {
        // Two real Completeness.reason() values from the same conversation, both flagging the
        // draft's unattributed broker-count claim, worded differently round to round.
        String first = "Fabrication: the draft states 'one active broker (Broker 1)' and 'Broker 1, "
                + "acting as the controller' as concrete facts, but the only specialist that returned "
                + "broker details (askInvestigation) did so after the draft was written — it cannot "
                + "have been sourced from the evidence available this round. Remove these specifics "
                + "or reframe them as uncertain. The rest of the draft is traceable to the evidence.";
        String second = "Fabrication: the draft states 'the cluster exists and has a single broker "
                + "(Broker 1), which is currently acting as the controller' as a confirmed fact, but "
                + "the only evidence for broker count/controller status comes from the investigation "
                + "specialist's raw output, which was not synthesised into the draft's main narrative. "
                + "The draft must either remove this unsupported detail or explicitly frame it as "
                + "provisional. The rest of the draft's claims about missing metrics are backed by evidence.";
        assertThat(KafkaInspector.isSameGap(FABRICATION, first, FABRICATION, second)).isTrue();
    }

    @Test
    void isSameGapReturnsFalseForUnrelatedReasonsEvenWithSameCategory() {
        // Both are genuine fabrication complaints, but about entirely different facts — the
        // category matching alone must not be treated as "same gap".
        String first = "The draft claims the topic has 12 partitions, but no admin tool call "
                + "returned a partition count for this topic.";
        String second = "The draft claims consumer group orders-svc is in a Stable state, but "
                + "the evidence shows the group was never described.";
        assertThat(KafkaInspector.isSameGap(FABRICATION, first, FABRICATION, second)).isFalse();
    }

    @Test
    void isSameGapReturnsFalseWhenCategoryDiffersEvenIfWordingOverlaps() {
        // Same wording as the "near-identical" case above would normally pass the text-overlap
        // check on its own — the category gate must still block it when the reviewer changed
        // what KIND of gap it's flagging.
        String reason = "The draft states 'the cluster exists and has a single broker (Broker 1)' "
                + "as a confirmed fact not backed by the evidence.";
        assertThat(KafkaInspector.isSameGap(FABRICATION, reason, MISSING_INFO, reason)).isFalse();
    }

    @Test
    void isSameGapReturnsFalseWhenEitherCategoryIsMissing() {
        String reason = "Fabrication: the draft states a fact not backed by evidence.";
        assertThat(KafkaInspector.isSameGap(null, reason, FABRICATION, reason)).isFalse();
        assertThat(KafkaInspector.isSameGap(FABRICATION, reason, "", reason)).isFalse();
    }

    @Test
    void isSameGapReturnsFalseForUnrelatedReasons() {
        String first = "Fabrication: the draft states an exact root cause no specialist gave.";
        String second = "Missing information: the draft never explains the current throughput of the cluster.";
        assertThat(KafkaInspector.isSameGap(FABRICATION, first, MISSING_INFO, second)).isFalse();
    }

    @Test
    void isSameGapReturnsFalseWhenEitherReasonHasNoSignificantWords() {
        assertThat(KafkaInspector.isSameGap(FABRICATION, "ok no", FABRICATION, "no ok")).isFalse();
    }
}
