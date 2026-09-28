package com.lynra.kafkatower.chatbot;

import com.lynra.kafkatower.service.PromptAnalyticsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PromptClassificationToolTest {

    @Mock
    private PromptAnalyticsService analyticsService;

    private PromptClassificationTool tool;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void init() {
        tool = new PromptClassificationTool(analyticsService);
    }

    @Test
    void classifyAndRecordPassesAuthenticatedUsername() {
        init();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));

        tool.classifyAndRecord("consumer_lag", "lag,group", "KafkaInvestigator");

        verify(analyticsService).record("consumer_lag", "lag,group", "KafkaInvestigator", "alice");
    }

    @Test
    void classifyAndRecordFallsBackToAnonymousWhenNoAuthenticationIsPresent() {
        init();

        tool.classifyAndRecord("topic_ownership", "topic", "KadycInspector");

        verify(analyticsService).record("topic_ownership", "topic", "KadycInspector", "anonymous");
    }
}
