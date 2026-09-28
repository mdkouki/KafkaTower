package com.lynra.kafkatower.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResponseTruncatorTest {

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
}
