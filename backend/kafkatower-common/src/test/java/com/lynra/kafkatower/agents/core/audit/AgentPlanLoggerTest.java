package com.lynra.kafkatower.agents.core.audit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.embabel.agent.api.event.AgentProcessCreationEvent;
import com.embabel.agent.api.event.AgentProcessPlanFormulatedEvent;
import com.embabel.agent.core.Agent;
import com.embabel.agent.core.AgentProcess;
import com.embabel.plan.Action;
import com.embabel.plan.Goal;
import com.embabel.plan.Plan;
import com.embabel.plan.WorldState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentPlanLoggerTest {

    private final AgentPlanLogger logger = new AgentPlanLogger();
    private ListAppender<ILoggingEvent> appender;
    private Logger targetLogger;

    @Mock
    private AgentProcess agentProcess;
    @Mock
    private Agent agent;
    @Mock
    private WorldState worldState;
    @Mock
    private Plan plan;
    @Mock
    private Goal goal;
    @Mock
    private Action askInvestigation;

    @BeforeEach
    void attachAppender() {
        targetLogger = (Logger) LoggerFactory.getLogger(AgentPlanLogger.class);
        appender = new ListAppender<>();
        appender.start();
        targetLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        targetLogger.detachAppender(appender);
    }

    @Test
    void logsGoalAndActionsWhenPlanIsFormulated() {
        when(agentProcess.getAgent()).thenReturn(agent);
        when(agent.getName()).thenReturn("KafkaInspector");
        when(plan.getGoal()).thenReturn(goal);
        when(goal.getName()).thenReturn("answer");
        when(askInvestigation.getName()).thenReturn("KafkaInspector.answer");
        when(plan.getActions()).thenReturn(List.of(askInvestigation));

        logger.onProcessEvent(new AgentProcessPlanFormulatedEvent(agentProcess, worldState, plan));

        assertThat(appender.list).hasSize(1);
        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message).contains("KafkaInspector").contains("answer").contains("KafkaInspector.answer");
    }

    @Test
    void ignoresOtherProcessEvents() {
        logger.onProcessEvent(new AgentProcessCreationEvent(agentProcess));

        assertThat(appender.list).isEmpty();
    }
}
