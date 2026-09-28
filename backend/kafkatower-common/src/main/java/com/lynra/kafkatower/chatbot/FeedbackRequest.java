package com.lynra.kafkatower.chatbot;

public record FeedbackRequest(
        String sessionId,
        String userPrompt,
        String agentAnswer,
        String feedback
) {}
