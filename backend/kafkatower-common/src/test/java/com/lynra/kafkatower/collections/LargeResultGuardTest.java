package com.lynra.kafkatower.collections;

import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.callback.AfterToolCallContext;
import com.embabel.agent.api.tool.callback.ToolCallInspector;
import com.embabel.chat.ToolCall;
import com.lynra.kafkatower.tools.BulkCollectionTools;
import com.lynra.kafkatower.utils.ToolResultLimiter;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class LargeResultGuardTest {

    private final BulkCollectionTools bulkCollectionTools = mock(BulkCollectionTools.class);
    private final ToolResultLimiter toolResultLimiter = mock(ToolResultLimiter.class);
    private final LargeResultGuard guard = new LargeResultGuard(bulkCollectionTools, toolResultLimiter);
    private final PromptRunner runner = mock(PromptRunner.class, Answers.RETURNS_SELF);
    private final List<ToolCallInspector> inspectors = new ArrayList<>();

    private void recordInspectors() {
        doAnswer(call -> {
            for (Object inspector : call.getArguments()) {
                inspectors.add((ToolCallInspector) inspector);
            }
            return runner;
        }).when(runner).withToolCallInspectors(any(ToolCallInspector[].class));
    }

    private void toolReturned(String tool, String arguments, String result) {
        AfterToolCallContext context = new AfterToolCallContext(new ToolCall("id", tool, arguments), Tool.Result.text(result), result, 1L);
        inspectors.forEach(inspector -> inspector.afterToolCall(context));
    }

    @Test
    void theSpecialistGetsTheBulkToolAndTheLimiterOnItsRunner() {
        doAnswer(call -> "answer").when(runner).createObject("q", String.class);

        assertThat(guard.answer(runner, "q")).isEqualTo("answer");

        verify(runner).withToolObject(bulkCollectionTools);
        verify(runner).withToolLoopTransformers(toolResultLimiter);
    }

    @Test
    void anAnswerWrittenFromASnippetIsSentBackUntilTheListIsOpened() {
        recordInspectors();
        doAnswer(call -> {
            toolReturned("findTopics", "{}", "... Collection key: aaaa1111 (6 items).");
            return "5 of 6 topics";
        }).when(runner).createObject("q", String.class);
        doAnswer(call -> {
            toolReturned("bulkOperation", "{\"collectionKey\":\"aaaa1111\"}", "all six");
            return "all 6 topics";
        }).when(runner).createObject(org.mockito.ArgumentMatchers.startsWith("Original question"), eq(String.class));

        assertThat(guard.answer(runner, "q")).isEqualTo("all 6 topics");

        verify(runner, times(2)).createObject(any(String.class), eq(String.class));
    }
}
