package com.lynra.kafkatower.chatbot;

import com.lynra.kafkatower.config.ConditionalOnAssistantEnabled;
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

@ConditionalOnAssistantEnabled
@Service
public class ChatService {

    private static final int MAX_HISTORY_TURNS = 5;
    private static final int MAX_ANSWER_SNIPPET = 400;

    private record ConversationTurn(String user, String agent) {}

    private final AgentPlatform agentPlatform;
    private final AsyncPromptClassifier promptClassifier;
    // keyed by userId:sessionId
    private final Map<String, Deque<ConversationTurn>> sessionHistory = new ConcurrentHashMap<>();
    private volatile Agent kafkaInspectorAgent;

    public ChatService(AgentPlatform agentPlatform, AsyncPromptClassifier promptClassifier) {
        this.agentPlatform = agentPlatform;
        this.promptClassifier = promptClassifier;
    }

    // Opportunistic warm-up: deployment and this listener both fire on ContextRefreshedEvent in no
    // guaranteed order, so the agent may not be deployed yet; agent() resolves it lazily in that case.
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
                    // Unlike init(), a request that still finds no agent is a real error: deployment
                    // has finished by the time any request can reach here.
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

        // Fire-and-forget analytics on its own pool; takes userId explicitly because
        // SecurityContextHolder is empty on the pool thread.
        promptClassifier.classifyAsync(message, userId);

        return Flux.<String>create(sink -> {
            // Embabel dispatches lifecycle events only to AgenticEventListener beans, so nothing is
            // attached to ProcessOptions; the status listener scopes itself to this request via a
            // ThreadLocal, bracketed by register/clear around the synchronous run() below.
            AgentStatusEventListener.register(sink::next);

            // KafkaInspector suppresses TokenStreamSink forwarding for the whole request, so in practice
            // nothing streams here and the fallback below sends the final answer as one chunk.
            AtomicBoolean tokenStreamed = new AtomicBoolean(false);
            TokenStreamSink.register(token -> {
                tokenStreamed.set(true);
                sink.next(token);
            });
            try {
                AgentProcess process = agentPlatform.createAgentProcessFrom(
                        agent(), ProcessOptions.DEFAULT, new UserMessage(messageWithHistory));
                process.run();
                // resultOfType throws if the process produced no AgentAnswer, after logging the
                // blackboard content so the failure stays diagnosable.
                AgentAnswer answer = AgentResultLogger.resultOfType(process, AgentAnswer.class, "root");
                String answerText = answer.text();
                saveToHistory(contextId, message, answerText);
                if (!tokenStreamed.get()) {
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
