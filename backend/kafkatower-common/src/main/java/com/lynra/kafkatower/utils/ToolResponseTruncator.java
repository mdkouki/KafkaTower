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
    private static final String DEFAULT_ADVICE =
            "Narrow your query with more specific filters or labels to reduce the result set.";

    /**
     * Intercepts @LlmTool methods and truncates String return values that exceed MAX_CHARS,
     * keeping raw tool output within the LLM's effective context window. Deliberately excludes
     * {@code SubAgentTools} — its @LlmTool methods return a specialist's finished, synthesized
     * answer, not raw tool output, and the truncation notice below ("narrow your query with
     * more specific filters") is meaningless advice for a natural-language answer and can send
     * the orchestrator back to re-ask the specialist. Also excludes @Action (agent entry points
     * return typed records, not String, so matching them was a no-op that only forced CGLIB
     * proxying of every @Agent class for no benefit).
     */
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

    /**
     * Same cap/log/notice mechanics as {@link #truncateIfNeeded(String, String)}, with
     * caller-supplied advice text — used by non-{@code @LlmTool} callers (e.g.
     * {@link com.lynra.kafkatower.agents.specialists.config.ReconnectingMcpTool}, which this AOP aspect
     * can't reach) that want guidance tailored to their own tool's parameters instead of the
     * generic default.
     */
    public static String truncateIfNeeded(String toolName, String response, String advice) {
        if (response == null || response.length() <= MAX_CHARS) return response;
        log.warn("Tool '{}' response truncated: {} chars -> {} chars", toolName, response.length(), MAX_CHARS);
        return response.substring(0, MAX_CHARS)
                + "\n[TRUNCATED: response exceeded " + MAX_CHARS + " characters. " + advice + "]";
    }
}
