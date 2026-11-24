package com.lynra.kafkatower.agents.specialists.kafka;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminSnapshotTest {

    @Test
    void aNoVerdictMeansMetricsAreNotNeededAndTheLineIsRemovedFromTheSummary() {
        AdminSnapshot snapshot = AdminSnapshot.fromResponse("18 topics found.\nMETRICS_NEEDED: no");

        assertThat(snapshot.metricsNeeded()).isFalse();
        assertThat(snapshot.summary()).isEqualTo("18 topics found.");
    }

    @Test
    void aYesVerdictMeansMetricsAreNeeded() {
        AdminSnapshot snapshot = AdminSnapshot.fromResponse("Group g1 is Stable, lag 4000.\nMETRICS_NEEDED: yes");

        assertThat(snapshot.metricsNeeded()).isTrue();
        assertThat(snapshot.summary()).isEqualTo("Group g1 is Stable, lag 4000.");
    }

    @Test
    void theVerdictIsReadIgnoringCaseAndSurroundingWhitespace() {
        assertThat(AdminSnapshot.fromResponse("x\n  metrics_needed:   NO  ").metricsNeeded()).isFalse();
    }

    @Test
    void aMissingVerdictDefaultsToNeededSoTheMetricsStepIsNeverSkippedByAccident() {
        AdminSnapshot snapshot = AdminSnapshot.fromResponse("18 topics found.");

        assertThat(snapshot.metricsNeeded()).isTrue();
        assertThat(snapshot.summary()).isEqualTo("18 topics found.");
    }

    @Test
    void aMalformedVerdictDefaultsToNeeded() {
        assertThat(AdminSnapshot.fromResponse("x\nMETRICS_NEEDED: maybe").metricsNeeded()).isTrue();
    }

    @Test
    void aVerdictInsideAFindingLineIsNotTreatedAsTheVerdict() {
        assertThat(AdminSnapshot.fromResponse("The note says METRICS_NEEDED: no for this topic.").metricsNeeded()).isTrue();
    }

    @Test
    void theLastVerdictWinsWhenTheModelRepeatsIt() {
        assertThat(AdminSnapshot.fromResponse("METRICS_NEEDED: yes\nmore\nMETRICS_NEEDED: no").metricsNeeded()).isFalse();
    }
}
