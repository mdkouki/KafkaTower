package com.lynra.kafkatower.chatbot;

import com.embabel.agent.api.event.AgentProcessCreationEvent;
import com.embabel.agent.api.event.ObjectAddedEvent;
import com.embabel.agent.core.Agent;
import com.embabel.agent.core.AgentProcess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentStatusEventListenerTest {

    private final AgentStatusEventListener listener = new AgentStatusEventListener();

    @Mock
    private AgentProcess rootProcess;
    @Mock
    private AgentProcess subProcess;
    @Mock
    private Agent subAgent;

    @AfterEach
    void clear() {
        AgentStatusEventListener.clear();
    }

    @Test
    void skipsTheFirstCreationEventAsTheRootProcessItself() {
        List<String> pushed = new ArrayList<>();
        AgentStatusEventListener.register(pushed::add);

        listener.onProcessEvent(new AgentProcessCreationEvent(rootProcess));

        assertThat(pushed).isEmpty();
    }

    @Test
    void pushesAStatusLabelForASpecialistCreatedAfterTheRoot() {
        when(subProcess.getAgent()).thenReturn(subAgent);
        when(subAgent.getName()).thenReturn("KafkaInvestigator");
        List<String> pushed = new ArrayList<>();
        AgentStatusEventListener.register(pushed::add);

        listener.onProcessEvent(new AgentProcessCreationEvent(rootProcess));
        listener.onProcessEvent(new AgentProcessCreationEvent(subProcess));

        assertThat(pushed).containsExactly(AgentStatusEventListener.STATUS_PREFIX + "investigating_cluster");
    }

    @Test
    void doesNothingWhenNoSinkIsRegisteredOnThisThread() {
        listener.onProcessEvent(new AgentProcessCreationEvent(rootProcess));
        // no exception, nothing to assert — proves it doesn't leak across unrelated threads/tests
    }

    @Test
    void ignoresNonCreationEvents() {
        List<String> pushed = new ArrayList<>();
        AgentStatusEventListener.register(pushed::add);

        listener.onProcessEvent(new ObjectAddedEvent(rootProcess, "some object"));

        assertThat(pushed).isEmpty();
    }
}
