package com.lynra.kafkatower.collections.bulk;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkOperationsTest {

    private static final List<String> LAGS = List.of("g1 lag=40", "g2 lag=5", "g3 lag=1000", "g4 idle", "g5 lag=7.5");

    private static List<String> itemsOf(BulkOperation operation, List<String> input, String expression, String parameter) {
        return ((BulkResult.Items) operation.apply(input, new BulkArguments(expression, parameter))).items();
    }

    private static String valueOf(BulkOperation operation, List<String> input, String expression, String parameter) {
        return ((BulkResult.Value) operation.apply(input, new BulkArguments(expression, parameter))).text();
    }

    private static Map<String, List<String>> groupsOf(BulkOperation operation, List<String> input, String expression) {
        return ((BulkResult.Groups) operation.apply(input, new BulkArguments(expression, null))).groups();
    }

    @Test
    void countReturnsTheNumberOfItems() {
        assertThat(valueOf(new CountOperation(), LAGS, null, null)).isEqualTo("5");
        assertThat(valueOf(new CountOperation(), LAGS, " ", null)).isEqualTo("5");
    }

    @Test
    void countWithAnExpressionCountsOnlyTheMatchingItems() {
        assertThat(valueOf(new CountOperation(), LAGS, "lag=", null)).isEqualTo("4");
        assertThat(valueOf(new CountOperation(), LAGS, "^g1 ", null)).isEqualTo("1");
        assertThat(valueOf(new CountOperation(), LAGS, "^nothing", null)).isEqualTo("0");
    }

    @Test
    void countRejectsAnInvalidExpression() {
        assertThatThrownBy(() -> valueOf(new CountOperation(), LAGS, "lag=(", null))
                .isInstanceOf(BulkOperationException.class).hasMessageContaining("Invalid regex");
    }

    @Test
    void allReturnsEveryItemEvenBeyondTheInlineLimit() {
        List<String> many = List.of("1", "2", "3", "4", "5", "6", "7", "8");

        BulkResult result = new AllOperation(10).apply(many, new BulkArguments(null, null));

        assertThat(result).isEqualTo(new BulkResult.Everything(many));
    }

    @Test
    void allRefusesACollectionLargerThanTheConfiguredMaximum() {
        assertThatThrownBy(() -> new AllOperation(3).apply(List.of("1", "2", "3", "4"), new BulkArguments(null, null)))
                .isInstanceOf(BulkOperationException.class).hasMessageContaining("4 items").hasMessageContaining("chunk");
    }

    @Test
    void sliceReturnsTheRequestedPageAndDefaultsToTheFirstFive() {
        List<String> ten = List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");

        assertThat(itemsOf(new SliceOperation(), ten, null, null)).containsExactly("0", "1", "2", "3", "4");
        assertThat(itemsOf(new SliceOperation(), ten, "8", "5")).containsExactly("8", "9");
        assertThat(itemsOf(new SliceOperation(), ten, "20", "5")).isEmpty();
    }

    @Test
    void sliceRejectsANonNumericOffset() {
        assertThatThrownBy(() -> itemsOf(new SliceOperation(), LAGS, "abc", null))
                .isInstanceOf(BulkOperationException.class).hasMessageContaining("whole number");
    }

    @Test
    void chunkDividesTheListIntoConsecutiveChunks() {
        Map<String, List<String>> chunks = groupsOf(new ChunkOperation(), List.of("a", "b", "c", "d", "e"), "2");

        assertThat(chunks).containsExactly(
                Map.entry("chunk 1 (items 1-2)", List.of("a", "b")),
                Map.entry("chunk 2 (items 3-4)", List.of("c", "d")),
                Map.entry("chunk 3 (items 5-5)", List.of("e")));
    }

    @Test
    void chunkRequiresAPositiveSize() {
        assertThatThrownBy(() -> groupsOf(new ChunkOperation(), LAGS, null)).isInstanceOf(BulkOperationException.class);
        assertThatThrownBy(() -> groupsOf(new ChunkOperation(), LAGS, "0")).isInstanceOf(BulkOperationException.class);
    }

    @Test
    void filterKeepsMatchingItemsAnywhereInTheText() {
        assertThat(itemsOf(new FilterOperation(), LAGS, "lag=\\d{3,}", null)).containsExactly("g3 lag=1000");
    }

    @Test
    void filterRequiresAValidRegex() {
        assertThatThrownBy(() -> itemsOf(new FilterOperation(), LAGS, " ", null))
                .isInstanceOf(BulkOperationException.class).hasMessageContaining("requires an expression");
        assertThatThrownBy(() -> itemsOf(new FilterOperation(), LAGS, "lag=(", null))
                .isInstanceOf(BulkOperationException.class).hasMessageContaining("Invalid regex");
    }

    @Test
    void excludeDropsMatchingItems() {
        assertThat(itemsOf(new ExcludeOperation(), LAGS, "lag", null)).containsExactly("g4 idle");
    }

    @Test
    void filterNumberKeepsItemsWhoseNumberPassesTheComparison() {
        assertThat(itemsOf(new FilterNumberOperation(), LAGS, "lag=([\\d.]+)", "gt 10"))
                .containsExactly("g1 lag=40", "g3 lag=1000");
        assertThat(itemsOf(new FilterNumberOperation(), LAGS, "lag=([\\d.]+)", "lte 7.5"))
                .containsExactly("g2 lag=5", "g5 lag=7.5");
    }

    @Test
    void filterNumberValidatesItsArguments() {
        assertThatThrownBy(() -> itemsOf(new FilterNumberOperation(), LAGS, "lag=\\d+", "gt 1"))
                .hasMessageContaining("one capture group");
        assertThatThrownBy(() -> itemsOf(new FilterNumberOperation(), LAGS, "lag=(\\d+)", "above 1"))
                .hasMessageContaining("Unknown comparison");
        assertThatThrownBy(() -> itemsOf(new FilterNumberOperation(), LAGS, "lag=(\\d+)", "gt many"))
                .hasMessageContaining("not a number");
        assertThatThrownBy(() -> itemsOf(new FilterNumberOperation(), LAGS, "lag=(\\d+)", null))
                .hasMessageContaining("comparison");
    }

    @Test
    void sortOrdersByTheWholeItemByDefault() {
        assertThat(itemsOf(new SortOperation(), List.of("b", "c", "a"), null, null)).containsExactly("a", "b", "c");
        assertThat(itemsOf(new SortOperation(), List.of("b", "c", "a"), null, "desc")).containsExactly("c", "b", "a");
    }

    @Test
    void sortComparesNumericKeysAsNumbersAndPutsKeylessItemsLast() {
        assertThat(itemsOf(new SortOperation(), LAGS, "lag=([\\d.]+)", "desc"))
                .containsExactly("g3 lag=1000", "g1 lag=40", "g5 lag=7.5", "g2 lag=5", "g4 idle");
    }

    @Test
    void sortRejectsAnUnknownDirection() {
        assertThatThrownBy(() -> itemsOf(new SortOperation(), LAGS, null, "sideways"))
                .isInstanceOf(BulkOperationException.class).hasMessageContaining("asc");
    }

    @Test
    void distinctKeepsTheFirstOccurrenceInOrder() {
        assertThat(itemsOf(new DistinctOperation(), List.of("b", "a", "b", "a", "c"), null, null))
                .containsExactly("b", "a", "c");
    }

    @Test
    void groupByUsesTheCaptureGroupAndListsLargestGroupsFirst() {
        List<String> topics = List.of("orders.a", "payments.a", "orders.b", "misc", "orders.c", "payments.b");

        Map<String, List<String>> groups = groupsOf(new GroupByOperation(), topics, "^(\\w+)\\.");

        assertThat(groups).containsExactly(
                Map.entry("orders", List.of("orders.a", "orders.b", "orders.c")),
                Map.entry("payments", List.of("payments.a", "payments.b")),
                Map.entry("(no match)", List.of("misc")));
    }

    @Test
    void groupByWithoutACaptureGroupUsesTheWholeMatch() {
        Map<String, List<String>> groups = groupsOf(new GroupByOperation(), List.of("a1", "b2", "a3"), "[a-z]");

        assertThat(groups).containsOnlyKeys("a", "b");
        assertThat(groups.get("a")).containsExactly("a1", "a3");
    }

    @Test
    void mapRewritesEveryItemWithCaptureGroupReferences() {
        assertThat(itemsOf(new MapOperation(), LAGS, "^(\\S+) .*$", "$1")).containsExactly("g1", "g2", "g3", "g4", "g5");
    }

    @Test
    void mapAllowsAnEmptyReplacementButRequiresAParameter() {
        assertThat(itemsOf(new MapOperation(), List.of("a-1"), "-\\d", "")).containsExactly("a");
        assertThatThrownBy(() -> itemsOf(new MapOperation(), LAGS, "g", null)).hasMessageContaining("requires a parameter");
    }

    @Test
    void mapReportsAnInvalidReplacementReference() {
        assertThatThrownBy(() -> itemsOf(new MapOperation(), LAGS, "g(\\d)", "$9"))
                .isInstanceOf(BulkOperationException.class).hasMessageContaining("Invalid replacement");
    }

    @Test
    void flatMapSplitsEveryItemAndDropsBlankParts() {
        assertThat(itemsOf(new FlatMapOperation(), List.of("a, b,c", "d,, e", ""), ",\\s*", null))
                .containsExactly("a", "b", "c", "d", "e");
    }

    @Test
    void aggregateComputesOverTheItemsThatContainANumber() {
        String regex = "lag=([\\d.]+)";

        assertThat(valueOf(new AggregateOperation(), LAGS, regex, "sum")).isEqualTo("sum = 1052.5 (over 4 of 5 items)");
        assertThat(valueOf(new AggregateOperation(), LAGS, regex, "min")).isEqualTo("min = 5 (over 4 of 5 items)");
        assertThat(valueOf(new AggregateOperation(), LAGS, regex, "max")).isEqualTo("max = 1000 (over 4 of 5 items)");
        assertThat(valueOf(new AggregateOperation(), LAGS, regex, "avg")).isEqualTo("avg = 263.125 (over 4 of 5 items)");
    }

    @Test
    void aggregateExplainsWhenNoItemHasANumberAndRejectsUnknownFunctions() {
        assertThat(valueOf(new AggregateOperation(), List.of("idle"), "lag=(\\d+)", "sum")).contains("nothing to sum");
        assertThatThrownBy(() -> valueOf(new AggregateOperation(), LAGS, "lag=([\\d.]+)", "median"))
                .hasMessageContaining("sum, min, max or avg");
    }
}
