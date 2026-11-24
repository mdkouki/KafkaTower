package com.lynra.kafkatower.tools;

import com.lynra.kafkatower.collections.CollectionPresenter;
import com.lynra.kafkatower.collections.CollectionStore;
import com.lynra.kafkatower.collections.bulk.AggregateOperation;
import com.lynra.kafkatower.collections.bulk.AllOperation;
import com.lynra.kafkatower.collections.bulk.BulkOperationRegistry;
import com.lynra.kafkatower.collections.bulk.ChunkOperation;
import com.lynra.kafkatower.collections.bulk.CountOperation;
import com.lynra.kafkatower.collections.bulk.FilterOperation;
import com.lynra.kafkatower.collections.bulk.GroupByOperation;
import com.lynra.kafkatower.collections.bulk.SliceOperation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class BulkCollectionToolsTest {

    private static final Pattern KEY = Pattern.compile("[Cc]ollection key: ([0-9a-f]+)");

    private final CollectionStore store = new CollectionStore(600, 10_000);
    private final CollectionPresenter presenter = new CollectionPresenter(store, 5);
    private final BulkCollectionTools tools = new BulkCollectionTools(store, presenter,
            new BulkOperationRegistry(List.of(new CountOperation(), new SliceOperation(), new FilterOperation(),
                    new GroupByOperation(), new ChunkOperation(), new AggregateOperation(), new AllOperation(30))));

    private String storeTopics(int count) {
        return store.put(IntStream.rangeClosed(1, count)
                .mapToObj(i -> (i % 2 == 0 ? "orders." : "payments.") + i).toList());
    }

    private static String keyIn(String text) {
        Matcher matcher = KEY.matcher(text);
        assertThat(matcher.find()).as("a collection key in: " + text).isTrue();
        return matcher.group(1);
    }

    @Test
    void countAnswersWithTheNumberOnly() {
        assertThat(tools.bulkOperation(storeTopics(40), "count", "", "")).isEqualTo("40");
    }

    @Test
    void countWithAnExpressionAnswersHowManyItemsMatch() {
        assertThat(tools.bulkOperation(storeTopics(40), "count", "^orders", "")).isEqualTo("20");
    }

    @Test
    void aSmallResultIsReturnedInlineWithoutAKey() {
        String result = tools.bulkOperation(storeTopics(40), "slice", "10", "3");

        assertThat(result).isEqualTo("Result of slice (3):\n  - payments.11"
                + "\n  - orders.12\n  - payments.13\n");
    }

    @Test
    void aLargeResultIsStoredAgainSoItCanBeChained() {
        String filtered = tools.bulkOperation(storeTopics(40), "filter", "^orders", "");

        assertThat(filtered).startsWith("Result of filter (20):").contains("15 more not shown");
        assertThat(tools.bulkOperation(keyIn(filtered), "count", "", "")).isEqualTo("20");
    }

    @Test
    void groupByListsEachGroupWithItsOwnKey() {
        String groups = tools.bulkOperation(storeTopics(40), "group_by", "^(\\w+)\\.", "");

        assertThat(groups).startsWith("Result of group_by: groups (2):");
        assertThat(tools.bulkOperation(keyIn(groups), "count", "", "")).isEqualTo("20");
    }

    @Test
    void aGroupWithinTheLimitIsListedInFullInsteadOfStored() {
        String groups = tools.bulkOperation(store.put(List.of("a.1", "a.2", "b.1")), "group_by", "^(\\w+)\\.", "");

        assertThat(groups).contains("a (2): a.1, a.2").contains("b (1): b.1").doesNotContain("collection key");
    }

    @Test
    void chunkGivesEveryChunkItsOwnKey() {
        String chunks = tools.bulkOperation(storeTopics(12), "chunk", "6", "");

        assertThat(chunks).startsWith("Result of chunk: groups (2):").contains("chunk 1 (items 1-6) (6):");
    }

    @Test
    void allPrintsTheWholeCollectionWithoutASnippetOrAKey() {
        String result = tools.bulkOperation(storeTopics(12), "all", "", "");

        assertThat(result).startsWith("Result of all (12):").contains("  - payments.1\n").contains("  - orders.12\n")
                .doesNotContain("more not shown").doesNotContain("collection key");
    }

    @Test
    void allIsRefusedForACollectionOverTheMaximum() {
        assertThat(tools.bulkOperation(storeTopics(31), "all", "", "")).startsWith("Error:").contains("31 items");
    }

    @Test
    void anUnknownKeyTellsTheLlmToRerunTheOriginalTool() {
        assertThat(tools.bulkOperation("gone", "count", "", "")).startsWith("Error:").contains("unknown or has expired");
    }

    @Test
    void anUnknownOperationListsTheAvailableOnes() {
        assertThat(tools.bulkOperation(storeTopics(6), "explode", "", ""))
                .startsWith("Error: unknown operation 'explode'").contains("count").contains("filter");
    }

    @Test
    void operationNamesAreMatchedIgnoringCaseAndWhitespace() {
        assertThat(tools.bulkOperation(storeTopics(6), " COUNT ", "", "")).isEqualTo("6");
    }

    @Test
    void anArgumentTheOperationDoesNotUseIsRejectedInsteadOfIgnored() {
        String key = storeTopics(12);

        assertThat(tools.bulkOperation(key, "filter", "^orders", "count"))
                .isEqualTo("Error: 'filter' takes no parameter (got 'count'). Remove it and call again.");
        assertThat(tools.bulkOperation(key, "all", "^orders", "")).startsWith("Error: 'all' takes no expression");
        assertThat(tools.bulkOperation(key, "count", "", "regex_match")).startsWith("Error: 'count' takes no parameter");
    }

    @Test
    void blankArgumentsCountAsNotProvided() {
        assertThat(tools.bulkOperation(storeTopics(12), "all", "  ", null)).startsWith("Result of all (12):");
    }

    @Test
    void badArgumentsAreReportedAsAnErrorNotThrown() {
        assertThat(tools.bulkOperation(storeTopics(6), "filter", "(", "")).startsWith("Error: Invalid regex");
    }
}
