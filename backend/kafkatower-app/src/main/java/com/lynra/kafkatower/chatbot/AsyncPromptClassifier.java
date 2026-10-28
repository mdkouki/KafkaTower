package com.lynra.kafkatower.chatbot;

import com.lynra.kafkatower.config.ConditionalOnAssistantEnabled;
import com.lynra.kafkatower.agents.core.root.ClassificationResult;
import com.lynra.kafkatower.utils.SkillLoader;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Classifies a user's question for analytics (theme / keywords / likely specialist) without
 * sitting on the chat's critical path. The result never influences routing or the answer, so the
 * LLM call runs fire-and-forget on its own pool, outside the Embabel agent process: the chat
 * answer doesn't wait for it, and a failure here can never fail a chat request.
 * <p>
 * Deliberately a plain Spring AI {@link ChatClient} rather than an Embabel {@code Ai} — the
 * latter is bound to the running agent process and its thread, which this call must not touch.
 */
@ConditionalOnAssistantEnabled
@Component
public class AsyncPromptClassifier {

    private static final Logger log = LoggerFactory.getLogger(AsyncPromptClassifier.class);

    private final ChatClient chatClient;
    private final PromptClassificationTool classificationTool;
    private final Executor executor;
    private String systemPrompt;

    public AsyncPromptClassifier(ChatClient.Builder chatClientBuilder,
                                 PromptClassificationTool classificationTool,
                                 @Qualifier("promptClassificationExecutor") Executor executor) {
        this.chatClient = chatClientBuilder.build();
        this.classificationTool = classificationTool;
        this.executor = executor;
    }

    @PostConstruct
    void loadSkill() {
        systemPrompt = SkillLoader.loadContent("/skills/ROOT_ANALYTICS.md");
    }

    /**
     * @param message the user's raw question (no conversation history prepended)
     * @param userId  captured by the caller on the request thread — SecurityContextHolder is
     *                thread-local and empty on the pool thread this runs on
     */
    public void classifyAsync(String message, String userId) {
        try {
            executor.execute(() -> classify(message, userId));
        } catch (RejectedExecutionException e) {
            log.warn("Prompt classification dropped — executor saturated (analytics only, chat unaffected)");
        }
    }

    void classify(String message, String userId) {
        try {
            ClassificationResult result = chatClient.prompt()
                    .system(systemPrompt)
                    .user(message)
                    .call()
                    .entity(ClassificationResult.class);
            if (result == null) {
                log.warn("Prompt classification returned nothing — skipping analytics record");
                return;
            }
            classificationTool.classifyAndRecord(result.theme(), result.keywords(), result.targetAgent(), userId);
        } catch (Exception e) {
            log.warn("Prompt classification failed — skipping analytics record: {}", e.toString());
        }
    }
}
