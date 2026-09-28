package com.lynra.kafkatower.chatbot;

import com.lynra.kafkatower.service.PromptAnalyticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Records prompt-classification analytics. Called directly as a plain Java method from
 * {@code KafkaInspector.answer} with a {@link com.lynra.kafkatower.agents.core.root.ClassificationResult}
 * produced by a separate LLM call against {@code ROOT_ANALYTICS.md} — it is never invoked via
 * LLM tool-calling, so it intentionally carries no Embabel action/tool annotations.
 */
@Component
public class PromptClassificationTool {

    private static final Logger log = LoggerFactory.getLogger(PromptClassificationTool.class);

    private final PromptAnalyticsService analyticsService;

    public PromptClassificationTool(PromptAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    /**
     * @param theme       a short label describing what the user is asking about (e.g. 'consumer_lag', 'topic_ownership')
     * @param keywords    2 to 5 lowercase keywords extracted from the question, comma-separated
     * @param targetAgent name of the specialist(s) judged most relevant for analytics purposes only —
     *                    this does not route: KafkaMetricsInspector, KafkaInvestigator, or
     *                    KafkaLogInspector
     */
    public void classifyAndRecord(String theme, String keywords, String targetAgent) {
        // Capture the user here, on the request thread — analyticsService.record runs async on a
        // pool thread where SecurityContextHolder would be empty.
        analyticsService.record(theme, keywords, targetAgent, currentUserId());
        log.debug("Analytics queued — theme: {}, keywords: {}, agent: {}", theme, keywords, targetAgent);
    }

    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() ? auth.getName() : "anonymous";
    }
}
