package com.lynra.kafkatower.agents.core.audit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class LlmCallTimerTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logbackLogger;

    @BeforeEach
    void setUp() {
        logbackLogger = (Logger) LoggerFactory.getLogger(LlmCallTimerTest.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logbackLogger.detachAppender(appender);
    }

    @Test
    void returnsCallResultUnchanged() {
        String result = LlmCallTimer.timed(logbackLogger, "test-call", 10_000, () -> "answer");

        assertThat(result).isEqualTo("answer");
    }

    @Test
    void doesNotWarnWhenCallIsFasterThanThreshold() {
        LlmCallTimer.timed(logbackLogger, "fast-call", 10_000, () -> "answer");

        assertThat(appender.list).isEmpty();
    }

    @Test
    void warnsWhenCallIsSlowerThanThreshold() {
        LlmCallTimer.timed(logbackLogger, "slow-call", 0, () -> "answer");

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("slow-call");
    }

    @Test
    void propagatesExceptionFromCallAndStillEvaluatesTiming() {
        try {
            LlmCallTimer.timed(logbackLogger, "failing-call", 0, () -> {
                throw new RuntimeException("boom");
            });
        } catch (RuntimeException e) {
            assertThat(e).hasMessage("boom");
        }

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
    }
}
