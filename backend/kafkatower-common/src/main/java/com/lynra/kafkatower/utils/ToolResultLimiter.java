package com.lynra.kafkatower.utils;

import com.embabel.agent.api.tool.callback.AfterToolResultContext;
import com.embabel.agent.api.tool.callback.ToolLoopTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounds what the LLM reads from one tool call: a result over {@value #MAX_CHARS} characters is cut
 * off with advice on narrowing the request, and an empty result becomes a placeholder. Attach it to
 * a {@code PromptRunner} with {@code withToolLoopTransformers}; it applies to every tool the runner
 * offers, however the tool was built. A runner whose tools return finished answers rather than raw
 * data (the orchestrator) simply does not attach it.
 */
@Component
public class ToolResultLimiter implements ToolLoopTransformer {

    private static final Logger log = LoggerFactory.getLogger(ToolResultLimiter.class);
    public static final int MAX_CHARS = 8_000;
    // Spring AI refuses to build a tool-response message from empty text, which aborts the whole
    // tool loop; the framework then retries and the model walks into the same abort again.
    static final String EMPTY_RESULT_NOTICE = "(the tool returned no output)";
    private static final String DEFAULT_ADVICE =
            "Narrow your query with more specific filters or labels to reduce the result set.";

    private final Map<String, String> adviceByToolName = new HashMap<>();

    @Autowired
    public ToolResultLimiter(ObjectProvider<ToolTruncationAdvice> advice) {
        this(advice.orderedStream().toList());
    }

    ToolResultLimiter(List<ToolTruncationAdvice> advice) {
        advice.forEach(entry -> entry.toolNames().forEach(tool -> adviceByToolName.put(tool, entry.advice())));
    }

    @Override
    public String transformAfterToolResult(AfterToolResultContext context) {
        String tool = context.getToolCall().getName();
        String result = context.getResultAsString();
        if (result.isBlank()) {
            log.warn("Tool '{}' returned a blank response; substituting a placeholder", tool);
            return EMPTY_RESULT_NOTICE;
        }
        if (result.length() <= MAX_CHARS) {
            return result;
        }
        log.warn("Tool '{}' response truncated: {} chars -> {} chars", tool, result.length(), MAX_CHARS);
        return result.substring(0, MAX_CHARS) + "\n[TRUNCATED: response exceeded " + MAX_CHARS
                + " characters. " + adviceByToolName.getOrDefault(tool, DEFAULT_ADVICE) + "]";
    }
}
