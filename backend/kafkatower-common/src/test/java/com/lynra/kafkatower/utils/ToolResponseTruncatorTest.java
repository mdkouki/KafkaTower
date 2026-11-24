package com.lynra.kafkatower.utils;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolResponseTruncatorTest {

    @Test
    void blankResponseBecomesAPlaceholderSoTheToolLoopIsNotAborted() {
        assertThat(ToolResponseTruncator.truncateIfNeeded("myTool", "")).isEqualTo("(the tool returned no output)");
        assertThat(ToolResponseTruncator.truncateIfNeeded("myTool", "  \n")).isEqualTo("(the tool returned no output)");
    }

    @Test
    void returnsShortResponseUnchanged() {
        String response = "short response";

        assertThat(ToolResponseTruncator.truncateIfNeeded("myTool", response)).isEqualTo(response);
    }

    @Test
    void returnsResponseAtExactlyMaxCharsUnchanged() {
        String response = "a".repeat(ToolResponseTruncator.MAX_CHARS);

        assertThat(ToolResponseTruncator.truncateIfNeeded("myTool", response)).isEqualTo(response);
    }

    @Test
    void truncatesResponseExceedingMaxCharsAndAppendsNotice() {
        String response = "a".repeat(ToolResponseTruncator.MAX_CHARS + 500);

        String result = ToolResponseTruncator.truncateIfNeeded("myTool", response);

        assertThat(result).startsWith("a".repeat(ToolResponseTruncator.MAX_CHARS));
        assertThat(result).contains("[TRUNCATED: response exceeded " + ToolResponseTruncator.MAX_CHARS + " characters.");
    }

    @Test
    void returnsNullUnchanged() {
        assertThat(ToolResponseTruncator.truncateIfNeeded("myTool", null)).isNull();
    }

    @Test
    void usesCallerSuppliedAdviceInTheTruncationNotice() {
        String response = "a".repeat(ToolResponseTruncator.MAX_CHARS + 1);

        String result = ToolResponseTruncator.truncateIfNeeded("myTool", response, "Use a shorter window.");

        assertThat(result).endsWith("Use a shorter window.]");
    }

    @Test
    void aspectTruncatesStringResultsOfInterceptedTools() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(signature.getName()).thenReturn("myTool");
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.proceed()).thenReturn("a".repeat(ToolResponseTruncator.MAX_CHARS + 1));

        Object result = new ToolResponseTruncator().truncateToolResponse(joinPoint);

        assertThat((String) result).contains("[TRUNCATED:");
    }

    @Test
    void aspectLeavesNonStringResultsUntouched() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        List<String> typedResult = List.of("a");
        when(joinPoint.proceed()).thenReturn(typedResult);

        assertThat(new ToolResponseTruncator().truncateToolResponse(joinPoint)).isSameAs(typedResult);
    }
}
