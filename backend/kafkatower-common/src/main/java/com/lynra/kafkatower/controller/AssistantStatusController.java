package com.lynra.kafkatower.controller;

import com.lynra.kafkatower.config.LlmSettingsEnvironmentPostProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tells the frontend whether the AI assistant is running, so it can show a hint instead of a chat box. */
@RestController
@RequestMapping("/api/assistant")
public class AssistantStatusController {

    private final boolean enabled;

    public AssistantStatusController(
            @Value("${" + LlmSettingsEnvironmentPostProcessor.ASSISTANT_ENABLED + ":false}") boolean enabled) {
        this.enabled = enabled;
    }

    @GetMapping("/status")
    public Status status() {
        return new Status(enabled);
    }

    record Status(boolean enabled) {}
}
