package com.lynra.kafkatower.utils;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.callback.AfterToolResultContext;
import com.embabel.chat.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultLimiterTest {

    private static final String TOOL = "myTool";

    private final ToolResultLimiter limiter = new ToolResultLimiter(List.of());

    private static AfterToolResultContext resultOf(String tool, String text) {
        return new AfterToolResultContext(List.of(), 1, new ToolCall("id", tool, "{}"), Tool.Result.text(text), text);
    }

    private static ToolTruncationAdvice adviceFor(Set<String> tools, String advice) {
        return new ToolTruncationAdvice() {
            @Override
            public Set<String> toolNames() {
                return tools;
            }

            @Override
            public String advice() {
                return advice;
            }
        };
    }

    @Test
    void blankResultsBecomeAPlaceholderSoTheToolLoopDoesNotAbort() {
        assertThat(limiter.transformAfterToolResult(resultOf(TOOL, ""))).isEqualTo("(the tool returned no output)");
        assertThat(limiter.transformAfterToolResult(resultOf(TOOL, "  \n"))).isEqualTo("(the tool returned no output)");
    }

    @Test
    void resultsWithinTheLimitPassThroughUnchanged() {
        String response = "a".repeat(ToolResultLimiter.MAX_CHARS - 1);
        String exactlyAtLimit = "a".repeat(ToolResultLimiter.MAX_CHARS);

        assertThat(limiter.transformAfterToolResult(resultOf(TOOL, response))).isEqualTo(response);
        assertThat(limiter.transformAfterToolResult(resultOf(TOOL, exactlyAtLimit))).isEqualTo(exactlyAtLimit);
    }

    @Test
    void oversizedResultsAreCutAtTheLimitWithTheDefaultAdvice() {
        String response = "a".repeat(ToolResultLimiter.MAX_CHARS + 500);

        String result = limiter.transformAfterToolResult(resultOf(TOOL, response));

        assertThat(result).startsWith("a".repeat(ToolResultLimiter.MAX_CHARS));
        assertThat(result).doesNotContain("a".repeat(ToolResultLimiter.MAX_CHARS + 1));
        assertThat(result).contains("[TRUNCATED: response exceeded " + ToolResultLimiter.MAX_CHARS + " characters.")
                .contains("Narrow your query with more specific filters");
    }

    @Test
    void aToolWithItsOwnAdviceGetsThatAdviceInsteadOfTheDefault() {
        ToolResultLimiter withAdvice = new ToolResultLimiter(List.of(adviceFor(Set.of("query_range"), "Use a shorter window.")));
        String response = "a".repeat(ToolResultLimiter.MAX_CHARS + 1);

        assertThat(withAdvice.transformAfterToolResult(resultOf("query_range", response)))
                .contains("Use a shorter window.").doesNotContain("Narrow your query with more specific filters");
        assertThat(withAdvice.transformAfterToolResult(resultOf(TOOL, response)))
                .contains("Narrow your query with more specific filters").doesNotContain("Use a shorter window.");
    }
}
