package com.lynra.kafkatower.chatbot;

import com.lynra.kafkatower.agents.core.root.ClassificationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AsyncPromptClassifierTest {

    private ChatClient chatClient;
    private PromptClassificationTool tool;
    private AsyncPromptClassifier classifier;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        tool = mock(PromptClassificationTool.class);
        // Direct executor: runs inline so the test can assert without waiting on a pool.
        classifier = new AsyncPromptClassifier(builder, tool, Runnable::run);
        classifier.loadSkill();
    }

    @Test
    void recordsClassificationWithExplicitUserId() {
        when(chatClient.prompt().system(anyString()).user(anyString()).call().entity(ClassificationResult.class))
                .thenReturn(new ClassificationResult("consumer_lag", "lag,group", "KafkaInvestigator"));

        classifier.classifyAsync("why is my group lagging?", "alice");

        verify(tool).classifyAndRecord("consumer_lag", "lag,group", "KafkaInvestigator", "alice");
    }

    @Test
    void llmFailureNeverPropagatesNorRecords() {
        when(chatClient.prompt().system(anyString()).user(anyString()).call().entity(ClassificationResult.class))
                .thenThrow(new RuntimeException("gateway down"));

        assertDoesNotThrow(() -> classifier.classifyAsync("q", "alice"));

        verifyNoInteractions(tool);
    }

    @Test
    void nullResultIsSkipped() {
        when(chatClient.prompt().system(anyString()).user(anyString()).call().entity(ClassificationResult.class))
                .thenReturn(null);

        classifier.classifyAsync("q", "alice");

        verifyNoInteractions(tool);
    }

    @Test
    void saturatedExecutorDropsClassificationInsteadOfThrowing() {
        Executor saturated = r -> { throw new RejectedExecutionException("full"); };
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        AsyncPromptClassifier c = new AsyncPromptClassifier(builder, tool, saturated);
        c.loadSkill();

        assertDoesNotThrow(() -> c.classifyAsync("q", "alice"));

        verifyNoInteractions(tool);
    }

    @Test
    void overloadWithoutUserIdIsUntouched() {
        // Guards the original request-thread entry point against accidental removal.
        PromptClassificationTool real = new PromptClassificationTool(mock(com.lynra.kafkatower.service.PromptAnalyticsService.class));
        assertDoesNotThrow(() -> real.classifyAndRecord("t", "k", "a"));
    }
}
