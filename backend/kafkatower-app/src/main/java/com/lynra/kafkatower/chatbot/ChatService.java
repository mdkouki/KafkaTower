package com.lynra.kafkatower.chatbot;

import com.embabel.agent.core.Agent;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.ProcessOptions;
import com.lynra.kafkatower.agents.core.audit.AgentResultLogger;
import com.lynra.kafkatower.agents.core.root.AgentAnswer;
import com.lynra.kafkatower.agents.core.root.UserMessage;
import com.lynra.kafkatower.agents.core.streaming.TokenStreamSink;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ChatService {

    private static final int MAX_HISTORY_TURNS = 5;
    private static final int MAX_ANSWER_SNIPPET = 400;

    private record ConversationTurn(String user, String agent) {}

    private final AgentPlatform agentPlatform;
    // keyed by userId:sessionId
    private final Map<String, Deque<ConversationTurn>> sessionHistory = new ConcurrentHashMap<>();
    private volatile Agent kafkaInspectorAgent;

    public ChatService(AgentPlatform agentPlatform) {
        this.agentPlatform = agentPlatform;
    }

    /**
     * Opportunistic warm-up, not a correctness dependency: agent deployment and this listener
     * both fire on {@code ContextRefreshedEvent} with no guaranteed relative order, so this may
     * run before deployment completes. Leaving {@link #kafkaInspectorAgent} null here is safe —
     * {@link #agent()}'s lazy fallback resolves it on first real request instead.
     */
    @EventListener(ContextRefreshedEvent.class)
    void init() {
        kafkaInspectorAgent = resolveAgent().orElse(null);
    }

    private Optional<Agent> resolveAgent() {
        return agentPlatform.agents().stream()
                .filter(a -> a.getName().equals("KafkaInspector"))
                .findFirst();
    }

    private Agent agent() {
        if (kafkaInspectorAgent == null) {
            synchronized (this) {
                if (kafkaInspectorAgent == null) {
                    // Unlike init()'s opportunistic warm-up, a real request genuinely needing the
                    // agent and still not finding it deployed is a real, throw-worthy error —
                    // ContextRefreshedEvent dispatch (and therefore deployment) has unconditionally
                    // finished by the time any request can reach here.
                    kafkaInspectorAgent = resolveAgent()
                            .orElseThrow(() -> new IllegalStateException("KafkaInspector agent not deployed"));
                }
            }
        }
        return kafkaInspectorAgent;
    }

    public Flux<String> askAgent(String sessionId, String message) {
        String userId = Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .map(Authentication::getName)
                .orElse("anonymous");
        String contextId = userId + ":" + sessionId;

        String messageWithHistory = buildMessageWithHistory(contextId, message);

        return Flux.<String>create(sink -> {
            // AgentStatusEventListener and AgentPlanLogger are both Spring @Component beans —
            // Embabel dispatches process-lifecycle events (creation, plan formulation) only to
            // AgenticEventListener beans in the context, not to a listener attached via
            // ProcessOptions.withListener(...), so there is nothing to build or attach here.
            // AgentStatusEventListener scopes itself to this request with a ThreadLocal (see its
            // Javadoc) — register/clear bracket the synchronous run() call below exactly like
            // TokenStreamSink already does.
            AgentStatusEventListener.register(sink::next);

            // AgentProcess.run() executes synchronously on this thread. KafkaInspector's own
            // orchestration suppresses TokenStreamSink forwarding for the whole request (see
            // KafkaInspector.answer / SubAgentTools.run), so in practice nothing is streamed
            // here and the fallback below sends the final, already-guard-rail-validated answer
            // as a single chunk. The registration stays in place for any future case where an
            // agent streams outside that suppressed scope.
            AtomicBoolean tokenStreamed = new AtomicBoolean(false);
            TokenStreamSink.register(token -> {
                tokenStreamed.set(true);
                sink.next(token);
            });
            try {
                AgentProcess process = agentPlatform.createAgentProcessFrom(
                        agent(), ProcessOptions.DEFAULT, new UserMessage(messageWithHistory));
                process.run();
                // resultOfType throws (rather than returning null) if the process didn't
                // complete or didn't produce an AgentAnswer — AgentResultLogger logs the
                // actual blackboard content in that case before the exception below hits the
                // catch block and becomes a generic sink.error, so what the root agent actually
                // produced is still visible in the logs.
                AgentAnswer answer = AgentResultLogger.resultOfType(process, AgentAnswer.class, "root");
                String answerText = answer.text();
                saveToHistory(contextId, message, answerText);
                if (!tokenStreamed.get()) {
                    // Nothing was streamed (e.g. a static "unavailable" message bypassed the LLM) —
                    // fall back to sending the whole answer as one chunk.
                    sink.next(answerText);
                }
            } catch (Exception e) {
                sink.error(e);
                return;
            } finally {
                TokenStreamSink.clear();
                AgentStatusEventListener.clear();
            }
            sink.complete();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String buildMessageWithHistory(String contextId, String message) {
        Deque<ConversationTurn> history = sessionHistory.get(contextId);
        if (history == null || history.isEmpty()) {
            return message;
        }
        StringBuilder sb = new StringBuilder("Conversation history (for context only):\n");
        for (ConversationTurn turn : history) {
            sb.append("User: ").append(turn.user()).append("\n");
            sb.append("Assistant: ").append(truncate(turn.agent(), MAX_ANSWER_SNIPPET)).append("\n");
        }
        sb.append("\nCurrent question: ").append(message);
        return sb.toString();
    }

    private void saveToHistory(String contextId, String userMessage, String agentAnswer) {
        Deque<ConversationTurn> history = sessionHistory.computeIfAbsent(contextId, k -> new LinkedList<>());
        history.addLast(new ConversationTurn(userMessage, agentAnswer));
        while (history.size() > MAX_HISTORY_TURNS) {
            history.removeFirst();
        }
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
