package com.lynra.kafkatower.utils;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@Aspect
public class ToolResponseTruncator {

    private static final Logger log = LoggerFactory.getLogger(ToolResponseTruncator.class);
    public static final int MAX_CHARS = 8_000;
    // Spring AI refuses to build a tool-response message from empty text, which aborts the whole
    // tool loop; the framework then retries and the model walks into the same abort again.
    static final String EMPTY_RESULT_NOTICE = "(the tool returned no output)";
    private static final String DEFAULT_ADVICE =
            "Narrow your query with more specific filters or labels to reduce the result set.";

    // Excludes SubAgentTools: it returns a finished natural-language answer, for which the
    // "narrow your query" notice is meaningless and can send the orchestrator back to re-ask.
    // Excludes @Action: entry points return typed records, so matching them only forced CGLIB proxying.
    @Around("@annotation(com.embabel.agent.api.annotation.LlmTool) " +
            "&& !within(com.lynra.kafkatower.agents.core.root.SubAgentTools)")
    public Object truncateToolResponse(ProceedingJoinPoint joinPoint) throws Throwable {
        Object result = joinPoint.proceed();
        if (result instanceof String str) {
            return truncateIfNeeded(joinPoint.getSignature().getName(), str);
        }
        return result;
    }

    public static String truncateIfNeeded(String toolName, String response) {
        return truncateIfNeeded(toolName, response, DEFAULT_ADVICE);
    }

    public static String truncateIfNeeded(String toolName, String response, String advice) {
        if (response != null && response.isBlank()) {
            log.warn("Tool '{}' returned a blank response; substituting a placeholder", toolName);
            return EMPTY_RESULT_NOTICE;
        }
        if (response == null || response.length() <= MAX_CHARS) return response;
        log.warn("Tool '{}' response truncated: {} chars -> {} chars", toolName, response.length(), MAX_CHARS);
        return response.substring(0, MAX_CHARS)
                + "\n[TRUNCATED: response exceeded " + MAX_CHARS + " characters. " + advice + "]";
    }
}
