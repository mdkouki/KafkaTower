package com.lynra.kafkatower.collections;

import com.embabel.agent.api.common.PromptRunner;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.callback.AfterToolCallContext;
import com.embabel.chat.ToolCall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FullListEnforcerTest {

    private final PromptRunner runner = mock(PromptRunner.class);
    private final UnreadCollectionTracker tracker = new UnreadCollectionTracker();

    private void toolPrinted(String tool, String result) {
        tracker.afterToolCall(new AfterToolCallContext(new ToolCall("id", tool, "{}"), Tool.Result.text(result), result, 1L));
    }

    @Test
    void anAnswerWithNoUnopenedCollectionIsReturnedAsIs() {
        when(runner.createObject("q", String.class)).thenReturn("complete");

        assertThat(FullListEnforcer.answer(runner, "q", tracker)).isEqualTo("complete");

        verify(runner, times(1)).createObject(any(String.class), eq(String.class));
    }

    @Test
    void anUnopenedCollectionTriggersAFollowUpNamingTheKeyAndThePreviousAnswer() {
        when(runner.createObject("q", String.class)).thenAnswer(call -> {
            toolPrinted("findTopics", "... Collection key: aaaa1111 (6 items).");
            return "5 of 6 topics";
        });
        when(runner.createObject(org.mockito.ArgumentMatchers.startsWith("Original question"), eq(String.class)))
                .thenReturn("all 6 topics");

        assertThat(FullListEnforcer.answer(runner, "q", tracker)).isEqualTo("all 6 topics");

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(runner, times(2)).createObject(prompts.capture(), eq(String.class));
        assertThat(prompts.getAllValues().get(1)).contains("aaaa1111").contains("findTopics")
                .contains("5 of 6 topics").contains("bulkOperation");
    }

    @Test
    void followUpsAreCappedWhenTheModelKeepsIgnoringTheCollections() {
        when(runner.createObject(any(String.class), eq(String.class))).thenAnswer(call -> {
            toolPrinted("findTopics", "Collection key: " + Long.toHexString(System.nanoTime()));
            return "still partial";
        });

        assertThat(FullListEnforcer.answer(runner, "q", tracker)).isEqualTo("still partial");

        verify(runner, times(1 + FullListEnforcer.MAX_FOLLOW_UPS)).createObject(any(String.class), eq(String.class));
    }
}
