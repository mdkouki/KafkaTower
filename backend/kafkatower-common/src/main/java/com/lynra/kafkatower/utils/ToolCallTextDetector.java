package com.lynra.kafkatower.utils;

import java.util.regex.Pattern;

/**
 * Recognises a model reply that is a tool call written out as text. Small local models do this
 * in steps where no tools are offered, because the prompt describes tools they cannot call; the
 * text is then mistaken for an answer by whoever reads it.
 */
public final class ToolCallTextDetector {

    // "[Tool Call]" and "**Tool Call**" headers, the "<|tool_call>" control token, and the
    // "tool_name" key of the JSON these models emit.
    private static final Pattern TOOL_CALL_TEXT = Pattern.compile(
            "(?i)\\[tool[ _]?call]|\\*\\*tool[ _]?call\\*\\*|<\\|?tool_call\\|?>|\"tool_name\"\\s*:");

    private ToolCallTextDetector() {
    }

    public static boolean containsToolCall(String reply) {
        return reply != null && TOOL_CALL_TEXT.matcher(reply).find();
    }
}
