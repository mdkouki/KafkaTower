package com.lynra.kafkatower.collections;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionPresenterTest {

    private static final Pattern KEY = Pattern.compile("[Cc]ollection key: ([0-9a-f]+)");

    private final CollectionStore store = new CollectionStore(Duration.ofMinutes(10), 1_000, () -> 0L);
    private final CollectionPresenter presenter = new CollectionPresenter(store, 5);

    private static List<String> items(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> "item-" + i).toList();
    }

    private String keyIn(String text) {
        Matcher matcher = KEY.matcher(text);
        assertThat(matcher.find()).as("a collection key in: " + text).isTrue();
        return matcher.group(1);
    }

    @Test
    void blockWithinTheLimitListsEveryItemAndStoresNothing() {
        String text = presenter.block("Topics", items(5));

        assertThat(text).isEqualTo("Topics (5):\n  - item-1\n  - item-2\n  - item-3\n  - item-4\n  - item-5\n");
    }

    @Test
    void blockOverTheLimitShowsASnippetTheCountAndAKeyToTheFullList() {
        String text = presenter.block("Topics", items(6));

        assertThat(text).startsWith("Topics (6):\n  - item-1\n").contains("  - item-5\n")
                .doesNotContain("item-6").contains("1 more not shown").contains("bulkOperation");
        assertThat(store.find(keyIn(text))).contains(items(6));
    }

    @Test
    void completeBlockListsEveryItemWithoutStoringAnything() {
        String text = presenter.completeBlock("Everything", items(7));

        assertThat(text).startsWith("Everything (7):\n  - item-1\n").endsWith("  - item-7\n")
                .doesNotContain("more not shown");
    }

    @Test
    void emptyBlockSaysNone() {
        assertThat(presenter.block("Topics", List.of())).isEqualTo("Topics (0): (none)\n");
    }

    @Test
    void inlineWithinTheLimitJoinsEveryItem() {
        assertThat(presenter.inline("producers", List.of("a", "b"))).isEqualTo("producers (2): a, b");
    }

    @Test
    void inlineOverTheLimitShowsASnippetAndAKeyToTheFullList() {
        String text = presenter.inline("producers", items(8));

        assertThat(text).startsWith("producers (8): item-1, item-2, item-3, item-4, item-5, ... +3 more");
        assertThat(store.find(keyIn(text))).contains(items(8));
    }

    @Test
    void emptyInlineSaysNone() {
        assertThat(presenter.inline("producers", List.of())).isEqualTo("producers (0): (none)");
    }
}
