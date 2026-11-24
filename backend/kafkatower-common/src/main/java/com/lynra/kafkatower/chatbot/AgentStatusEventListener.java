package com.lynra.kafkatower.chatbot;

import com.lynra.kafkatower.config.ConditionalOnAssistantEnabled;
import com.embabel.agent.api.event.AgentProcessCreationEvent;
import com.embabel.agent.api.event.AgentProcessEvent;
import com.embabel.agent.api.event.AgentProcessFinishedEvent;
import com.embabel.agent.api.event.AgentProcessStuckEvent;
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
    // ThreadLocal would be empty there. The tracker is thread-safe and closed by clear(), so a
    // pooled thread that inherited it by accident can no longer push into a finished request.
    private static final ThreadLocal<ChatStatusTracker> TRACKER = new InheritableThreadLocal<>();

    public static void register(Consumer<String> statusSink) {
        TRACKER.set(new ChatStatusTracker(statusSink));
    }

    public static void emit(String label) {
        ChatStatusTracker tracker = TRACKER.get();
        if (tracker != null) {
            tracker.phaseStarted(label);
        }
    }

    public static void clear() {
        ChatStatusTracker tracker = TRACKER.get();
        if (tracker != null) {
            tracker.close();
        }
        TRACKER.remove();
    }

    @Override
    public void onProcessEvent(AgentProcessEvent event) {
        ChatStatusTracker tracker = TRACKER.get();
        if (tracker == null) {
            return;
        }
        if (event instanceof AgentProcessCreationEvent creation) {
            tracker.processCreated(creation.getProcessId(),
                    () -> toStatusLabel(creation.getAgentProcess().getAgent().getName()));
        } else if (event instanceof AgentProcessFinishedEvent || event instanceof AgentProcessStuckEvent) {
            tracker.processEnded(event.getProcessId());
        }
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
