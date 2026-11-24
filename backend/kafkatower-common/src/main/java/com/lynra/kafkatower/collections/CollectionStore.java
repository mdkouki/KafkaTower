package com.lynra.kafkatower.collections;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Short-lived holder for the full contents of a large tool result, so only a snippet reaches the
 * LLM. The LLM gets the key back and works on the collection through {@code BulkCollectionTools}.
 * Memory is bounded by the total number of stored items, not by the number of collections.
 */
@Component
public class CollectionStore {

    private static final int KEY_LENGTH = 8;

    private final Cache<String, List<String>> collections;

    @Autowired
    public CollectionStore(@Value("${agent.collections.ttl-seconds:600}") long ttlSeconds,
                           @Value("${agent.collections.max-items:200000}") long maxItems) {
        this(Duration.ofSeconds(ttlSeconds), maxItems, Ticker.systemTicker());
    }

    CollectionStore(Duration ttl, long maxItems, Ticker ticker) {
        this.collections = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumWeight(maxItems)
                .weigher((String key, List<String> items) -> Math.max(1, items.size()))
                .ticker(ticker)
                .build();
    }

    public String put(List<String> items) {
        List<String> snapshot = List.copyOf(items);
        String key = newKey();
        while (collections.asMap().putIfAbsent(key, snapshot) != null) {
            key = newKey();
        }
        return key;
    }

    public Optional<List<String>> find(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(collections.getIfPresent(key.trim()));
    }

    private static String newKey() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, KEY_LENGTH);
    }
}
