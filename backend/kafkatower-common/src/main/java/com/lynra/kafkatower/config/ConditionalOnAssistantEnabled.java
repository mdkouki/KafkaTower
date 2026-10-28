package com.lynra.kafkatower.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a bean that needs the LLM (Embabel or Spring AI). It exists only while the assistant is
 * enabled, i.e. {@code kafkatower.assistant.enabled=true} — set by
 * {@link LlmSettingsEnvironmentPostProcessor} once an admin has saved an API key, base URL and
 * model, and cleared (along with Embabel itself) while they are missing.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ConditionalOnProperty(name = LlmSettingsEnvironmentPostProcessor.ASSISTANT_ENABLED, havingValue = "true")
public @interface ConditionalOnAssistantEnabled {
}
