package com.lynra.kafkatower.chatbot;

import com.lynra.kafkatower.config.ConditionalOnAssistantEnabled;
import com.embabel.agent.api.event.AgentProcessCreationEvent;
import com.embabel.agent.api.event.AgentProcessEvent;
import com.embabel.agent.api.event.AgenticEventListener;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/*
 * Registered as a bean because ProcessOptions.withListener(...) never receives
 * AgentProcessCreationEvent (see AgentPlanLogger). A bean sees every process platform-wide, so it
 * scopes itself to the chat request with a ThreadLocal: AgentProcess.run() executes synchronously
 * on the calling thread, hence the registering thread is the one the callback fires on.
 */
@ConditionalOnAssistantEnabled
@Component
public class AgentStatusEventListener implements AgenticEventListener {

    public static final String STATUS_PREFIX = "__status__:";

    // Inheritable on purpose: with Embabel's PARALLEL tool loop (see application.yml) a specialist
    // sub-process is created on a virtual thread spawned from the request thread; a plain
    // ThreadLocal would be empty there. The sink (FluxSink via Flux.create) is thread-safe.
    private static final ThreadLocal<Consumer<String>> SINK = new InheritableThreadLocal<>();
    private static final ThreadLocal<Boolean> ROOT_SEEN = new InheritableThreadLocal<>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    public static void register(Consumer<String> statusSink) {
        SINK.set(statusSink);
        ROOT_SEEN.set(false);
    }

    public static void emit(String label) {
        Consumer<String> sink = SINK.get();
        if (sink != null) {
            sink.accept(STATUS_PREFIX + label);
        }
    }

    public static void clear() {
        SINK.remove();
        ROOT_SEEN.remove();
    }

    @Override
    public void onProcessEvent(AgentProcessEvent event) {
        if (!(event instanceof AgentProcessCreationEvent creation)) {
            return;
        }
        Consumer<String> sink = SINK.get();
        if (sink == null) {
            return;
        }
        if (!ROOT_SEEN.get()) {
            // The first creation event on this thread is the root process, which gets no label.
            ROOT_SEEN.set(true);
            return;
        }
        String agentName = creation.getAgentProcess().getAgent().getName();
        sink.accept(STATUS_PREFIX + toStatusLabel(agentName));
    }

    private static String toStatusLabel(String agentName) {
        return switch (agentName) {
            case "KafkaMetricsInspector" -> "analyzing_metrics";
            case "KafkaInvestigator" -> "investigating_cluster";
            case "KafkaLogInspector" -> "searching_logs";
            case "KafkaRegistryInspector" -> "checking_dependencies";
            default -> agentName.toLowerCase();
        };
    }
}
