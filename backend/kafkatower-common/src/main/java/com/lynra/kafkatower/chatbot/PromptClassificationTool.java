package com.lynra.kafkatower.chatbot;

import com.lynra.kafkatower.service.PromptAnalyticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/*
 * Called as a plain Java method from AsyncPromptClassifier, never via LLM tool-calling, so it
 * carries no Embabel annotations.
 */
@Component
public class PromptClassificationTool {

    private static final Logger log = LoggerFactory.getLogger(PromptClassificationTool.class);

    private final PromptAnalyticsService analyticsService;

    public PromptClassificationTool(PromptAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    public void classifyAndRecord(String theme, String keywords, String targetAgent) {
        // Captured on the request thread: analyticsService.record runs async where SecurityContextHolder is empty.
        classifyAndRecord(theme, keywords, targetAgent, currentUserId());
    }

    public void classifyAndRecord(String theme, String keywords, String targetAgent, String userId) {
        analyticsService.record(theme, keywords, targetAgent, userId);
        log.debug("Analytics queued — theme: {}, keywords: {}, agent: {}", theme, keywords, targetAgent);
    }

    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() ? auth.getName() : "anonymous";
    }
}
