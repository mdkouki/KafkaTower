package com.lynra.kafkatower.chatbot;

import com.lynra.kafkatower.config.ConditionalOnAssistantEnabled;
import com.embabel.agent.api.event.AgentProcessCreationEvent;
import com.embabel.agent.api.event.AgentProcessEvent;
import com.embabel.agent.api.event.AgenticEventListener;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Pushes an SSE status label (e.g. {@code searching_registry}) to the chat stream whenever a
 * specialist sub-agent process starts, so the UI can show progress during a multi-specialist
 * question.
 * <p>
 * Registered as a Spring {@code @Component} rather than attached per-process via
 * {@code ProcessOptions.withListener(...)} — the latter never receives
 * {@code AgentProcessCreationEvent} (see {@code AgentPlanLogger}'s Javadoc for how this was
 * verified), so a status listener wired that way would never fire regardless of how it's
 * threaded through to sub-agent processes. As a bean it receives every process's events
 * platform-wide, so it scopes itself to the current chat request with a {@link ThreadLocal},
 * the same pattern {@code TokenStreamSink} uses: {@code AgentProcess.run()} — for both the root
 * process and every sub-process {@code SubAgentTools.run} creates — executes synchronously on
 * the calling thread, so the thread that registered a sink here is the same thread this
 * listener's callback fires on.
 */
@ConditionalOnAssistantEnabled
@Component
public class AgentStatusEventListener implements AgenticEventListener {

    public static final String STATUS_PREFIX = "__status__:";

    // Inheritable on purpose: with Embabel's PARALLEL tool loop (see application.yml) a specialist
    // sub-process is created on a virtual thread spawned from the request thread, not on the
    // request thread itself. An ordinary ThreadLocal would be empty there and the specialist's
    // status label would silently vanish. The sink (FluxSink via Flux.create) is safe to call
    // from several threads.
    private static final ThreadLocal<Consumer<String>> SINK = new InheritableThreadLocal<>();
    private static final ThreadLocal<Boolean> ROOT_SEEN = new InheritableThreadLocal<>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    /** Registers the sink for the chat request running on the calling thread. */
    public static void register(Consumer<String> statusSink) {
        SINK.set(statusSink);
        ROOT_SEEN.set(false);
    }

    /**
     * Pushes a status label for the chat request running on the calling thread — for orchestration
     * phases that aren't a specialist process creation (drafting, reviewing, refining). No-op when
     * no sink is registered, so callers outside a chat request (tests, background work) are safe.
     */
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
            // The first creation event on this thread is the root process itself — no status
            // label needed for that; only specialist sub-processes created after it get one.
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
