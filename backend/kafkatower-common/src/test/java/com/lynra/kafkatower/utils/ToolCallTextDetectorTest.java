package com.lynra.kafkatower.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallTextDetectorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "[Tool Call]\n```json\n{\"tool_name\": \"listTopics\"}\n```",
            "**Tool Call**: ```json {\"parameters\": {}} ```",
            "<|tool_call>call:findTopicsByCriteria {\"cluster\": \"kafka-nonprod\"}",
            "{\n  \"tool_name\" : \"list_topics\"\n}",
            "[tool_call] listTopics"
    })
    void aToolCallWrittenOutAsTextIsRecognised(String reply) {
        assertThat(ToolCallTextDetector.containsToolCall(reply)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "## Kafka Investigation Report\n### Findings\n1. [Admin] There are 6 topics containing \"fulfillment\".",
            "The consumer group made a tool call to the registry last night, according to the ACL notes.",
            "Topic names: tool-calls.v1, tool_names.v2"
    })
    void aReportThatMerelyMentionsToolsIsNotFlagged(String reply) {
        assertThat(ToolCallTextDetector.containsToolCall(reply)).isFalse();
    }

    @Test
    void nullAndBlankRepliesAreNotToolCalls() {
        assertThat(ToolCallTextDetector.containsToolCall(null)).isFalse();
        assertThat(ToolCallTextDetector.containsToolCall("  ")).isFalse();
    }
}
