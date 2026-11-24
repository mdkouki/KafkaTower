package com.lynra.kafkatower.kafka;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class AdminClientErrorsTest {

    @Test
    void timeoutIsReportedAsUnreachableBroker() {
        assertThat(AdminClientErrors.describe(new TimeoutException(), 10))
                .isEqualTo("Error: the broker did not respond within 10s — the cluster may be unreachable.");
    }

    @Test
    void timeoutWrappedInExecutionExceptionIsUnwrapped() {
        assertThat(AdminClientErrors.describe(new ExecutionException(new TimeoutException()), 5))
                .contains("did not respond within 5s");
    }

    @Test
    void otherFailuresShowTheCauseTypeAndMessage() {
        var e = new ExecutionException(new IllegalStateException("boom"));

        assertThat(AdminClientErrors.describe(e, 10)).isEqualTo("Error during operation: IllegalStateException: boom");
    }

    @Test
    void aMissingMessageFallsBackToTheTypeName() {
        assertThat(AdminClientErrors.describe(new IllegalStateException(), 10))
                .isEqualTo("Error during operation: IllegalStateException: IllegalStateException");
    }

    @Test
    void anExecutionExceptionWithoutCauseIsDescribedItself() {
        assertThat(AdminClientErrors.describe(new ExecutionException("outer", null), 10))
                .startsWith("Error during operation: ExecutionException");
    }
}
