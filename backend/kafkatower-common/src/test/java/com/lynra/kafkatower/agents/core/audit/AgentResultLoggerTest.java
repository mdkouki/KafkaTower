package com.lynra.kafkatower.agents.core.audit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.AgentProcessStatusCode;
import com.embabel.agent.core.Blackboard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentResultLoggerTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger targetLogger;

    @Mock
    private AgentProcess process;
    @Mock
    private Blackboard blackboard;

    @BeforeEach
    void attachAppender() {
        targetLogger = (Logger) LoggerFactory.getLogger(AgentResultLogger.class);
        appender = new ListAppender<>();
        appender.start();
        targetLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        targetLogger.detachAppender(appender);
    }

    private record Dummy(String text) {}

    @Test
    void returnsTheValueDirectlyOnSuccessWithoutLogging() {
        when(process.resultOfType(Dummy.class)).thenReturn(new Dummy("hi"));

        Dummy result = AgentResultLogger.resultOfType(process, Dummy.class, "test-agent");

        assertThat(result.text()).isEqualTo("hi");
        assertThat(appender.list).isEmpty();
    }

    @Test
    void logsBlackboardContentAndRethrowsOnFailure() {
        IllegalStateException failure = new IllegalStateException("No value of type Dummy found");
        when(process.resultOfType(Dummy.class)).thenThrow(failure);
        when(process.getStatus()).thenReturn(AgentProcessStatusCode.COMPLETED);
        when(process.getFailureInfo()).thenReturn(null);
        when(process.getBlackboard()).thenReturn(blackboard);
        when(blackboard.getObjects()).thenReturn(List.of(new Dummy("unexpected-shape")));

        assertThatThrownBy(() -> AgentResultLogger.resultOfType(process, Dummy.class, "test-agent"))
                .isSameAs(failure);

        assertThat(appender.list).hasSize(1);
        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message).contains("test-agent").contains("Dummy").contains("COMPLETED")
                .contains("unexpected-shape");
    }
}
