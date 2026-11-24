package com.lynra.kafkatower.chatbot;

import com.embabel.agent.api.event.AgentProcessCompletedEvent;
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
    private AgentProcess otherSubProcess;
    @Mock
    private Agent subAgent;
    @Mock
    private Agent otherSubAgent;

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

    @Test
    void emitPushesPrefixedLabelToTheRegisteredSink() {
        List<String> pushed = new ArrayList<>();
        AgentStatusEventListener.register(pushed::add);

        AgentStatusEventListener.emit("reviewing_answer");

        assertThat(pushed).containsExactly(AgentStatusEventListener.STATUS_PREFIX + "reviewing_answer");
    }

    @Test
    void emitIsANoOpWithoutARegisteredSink() {
        AgentStatusEventListener.emit("drafting_answer"); // must not throw
    }

    @Test
    void emitReachesTheSinkFromAVirtualThreadSpawnedByTheRequestThread() throws Exception {
        List<String> pushed = java.util.Collections.synchronizedList(new ArrayList<>());
        AgentStatusEventListener.register(pushed::add);

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> AgentStatusEventListener.emit("analyzing_metrics")).get();
        }

        assertThat(pushed).containsExactly(AgentStatusEventListener.STATUS_PREFIX + "analyzing_metrics");
    }

    @Test
    void fallsBackToTheRootPhaseWhenTheLastSpecialistFinishes() {
        stubSpecialist(subProcess, subAgent, "sub-1", "KafkaLogInspector");
        List<String> pushed = new ArrayList<>();
        AgentStatusEventListener.register(pushed::add);

        AgentStatusEventListener.emit("drafting_answer");
        listener.onProcessEvent(new AgentProcessCreationEvent(rootProcess));
        listener.onProcessEvent(new AgentProcessCreationEvent(subProcess));
        listener.onProcessEvent(new AgentProcessCompletedEvent(subProcess));

        assertThat(pushed).containsExactly(
                label("drafting_answer"), label("searching_logs"), label("drafting_answer"));
    }

    @Test
    void keepsShowingARunningSpecialistWhenAnotherOneFinishes() {
        stubSpecialist(subProcess, subAgent, "sub-1", "KafkaMetricsInspector");
        stubSpecialist(otherSubProcess, otherSubAgent, "sub-2", "KafkaLogInspector");
        List<String> pushed = new ArrayList<>();
        AgentStatusEventListener.register(pushed::add);

        listener.onProcessEvent(new AgentProcessCreationEvent(rootProcess));
        listener.onProcessEvent(new AgentProcessCreationEvent(subProcess));
        listener.onProcessEvent(new AgentProcessCreationEvent(otherSubProcess));
        listener.onProcessEvent(new AgentProcessCompletedEvent(otherSubProcess));

        assertThat(pushed).containsExactly(
                label("analyzing_metrics"), label("searching_logs"), label("analyzing_metrics"));
    }

    @Test
    void doesNotRepeatTheRootPhaseWhileASpecialistIsStillRunning() {
        stubSpecialist(subProcess, subAgent, "sub-1", "KafkaInvestigator");
        List<String> pushed = new ArrayList<>();
        AgentStatusEventListener.register(pushed::add);

        listener.onProcessEvent(new AgentProcessCreationEvent(rootProcess));
        listener.onProcessEvent(new AgentProcessCreationEvent(subProcess));
        AgentStatusEventListener.emit("reviewing_answer");

        assertThat(pushed).containsExactly(label("investigating_cluster"));
    }

    @Test
    void ignoresTheFinishOfTheRootProcess() {
        List<String> pushed = new ArrayList<>();
        AgentStatusEventListener.register(pushed::add);

        listener.onProcessEvent(new AgentProcessCreationEvent(rootProcess));
        listener.onProcessEvent(new AgentProcessCompletedEvent(rootProcess));

        assertThat(pushed).isEmpty();
    }

    @Test
    void pushesNothingAfterTheRequestIsCleared() throws Exception {
        List<String> pushed = java.util.Collections.synchronizedList(new ArrayList<>());
        AgentStatusEventListener.register(pushed::add);
        var started = new java.util.concurrent.CountDownLatch(1);
        var cleared = new java.util.concurrent.CountDownLatch(1);

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var lateEmit = executor.submit(() -> {
                started.countDown();
                cleared.await();
                AgentStatusEventListener.emit("drafting_answer");
                return null;
            });
            started.await();
            AgentStatusEventListener.clear();
            cleared.countDown();
            lateEmit.get();
        }

        assertThat(pushed).isEmpty();
    }

    private static String label(String name) {
        return AgentStatusEventListener.STATUS_PREFIX + name;
    }

    private static void stubSpecialist(AgentProcess process, Agent agent, String id, String agentName) {
        when(process.getId()).thenReturn(id);
        when(process.getAgent()).thenReturn(agent);
        when(agent.getName()).thenReturn(agentName);
    }
}
