package com.lynra.kafkatower.collections;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.callback.AfterToolCallContext;
import com.embabel.chat.ToolCall;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnreadCollectionTrackerTest {

    private final UnreadCollectionTracker tracker = new UnreadCollectionTracker();

    private void record(String tool, String arguments, String result) {
        tracker.afterToolCall(new AfterToolCallContext(new ToolCall("id", tool, arguments), Tool.Result.text(result), result, 1L));
    }

    @Test
    void nothingIsUnreadWhenNoToolHandedOutAKey() {
        record("findTopics", "{}", "Topics (2):\n  - a\n  - b\n");

        assertThat(tracker.takeUnread()).isEmpty();
    }

    @Test
    void aKeyFromABlockOrAnInlineListIsUnreadUntilOpened() {
        record("findTopics", "{}", "Topics (9):\n  ... 4 more not shown. Collection key: aaaa1111 (9 items).\n");
        record("whoUsesTopic", "{}", "producers (7): a, b, c, d, e, ... +2 more [collection key: bbbb2222, use bulkOperation to access the content]");

        assertThat(tracker.takeUnread()).containsExactly(
                new UnreadCollectionTracker.UnreadCollection("aaaa1111", "findTopics"),
                new UnreadCollectionTracker.UnreadCollection("bbbb2222", "whoUsesTopic"));
    }

    @Test
    void openingAKeyWithBulkOperationMarksItRead() {
        record("findTopics", "{}", "Topics (9):\n  ... 4 more not shown. Collection key: aaaa1111 (9 items).\n");

        record("bulkOperation", "{\"collectionKey\":\"aaaa1111\",\"operation\":\"count\"}", "9");

        assertThat(tracker.takeUnread()).isEmpty();
    }

    @Test
    void keysPrintedByBulkOperationItselfAreNotDemanded() {
        record("bulkOperation", "{\"collectionKey\":\"aaaa1111\",\"operation\":\"filter\"}",
                "Result of filter (8):\n  ... 3 more not shown. Collection key: cccc3333 (8 items).\n");

        assertThat(tracker.takeUnread()).isEmpty();
    }

    @Test
    void takingUnreadForgetsThemSoEachKeyIsDemandedOnce() {
        record("findTopics", "{}", "Collection key: aaaa1111");

        assertThat(tracker.takeUnread()).hasSize(1);
        assertThat(tracker.takeUnread()).isEmpty();
    }
}
