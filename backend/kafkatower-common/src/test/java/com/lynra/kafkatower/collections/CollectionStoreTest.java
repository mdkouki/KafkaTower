package com.lynra.kafkatower.collections;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionStoreTest {

    private final AtomicLong nowNanos = new AtomicLong();
    private final CollectionStore store = new CollectionStore(Duration.ofMinutes(10), 100, nowNanos::get);

    @Test
    void storedItemsAreRetrievableByTheirKey() {
        String key = store.put(List.of("a", "b"));

        assertThat(store.find(key)).contains(List.of("a", "b"));
    }

    @Test
    void keysAreDistinctPerCollection() {
        assertThat(store.put(List.of("a"))).isNotEqualTo(store.put(List.of("a")));
    }

    @Test
    void keyWithSurroundingWhitespaceStillResolves() {
        String key = store.put(List.of("a"));

        assertThat(store.find("  " + key + "\n")).isPresent();
    }

    @Test
    void unknownOrMissingKeyIsEmpty() {
        assertThat(store.find("nope")).isEmpty();
        assertThat(store.find(null)).isEmpty();
    }

    @Test
    void collectionExpiresAfterTheTtl() {
        String key = store.put(List.of("a"));

        nowNanos.addAndGet(Duration.ofMinutes(9).toNanos());
        assertThat(store.find(key)).isPresent();

        nowNanos.addAndGet(Duration.ofMinutes(2).toNanos());
        assertThat(store.find(key)).isEmpty();
    }

    @Test
    void laterChangesToTheSourceListDoNotAffectTheStoredCollection() {
        List<String> source = new ArrayList<>(List.of("a"));
        String key = store.put(source);

        source.add("b");

        assertThat(store.find(key)).contains(List.of("a"));
    }
}
