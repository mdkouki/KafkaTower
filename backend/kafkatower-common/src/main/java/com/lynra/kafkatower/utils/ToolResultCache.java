package com.lynra.kafkatower.utils;

import com.embabel.agent.api.tool.Tool;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Short-TTL cache of tool results, so a repeated or concurrent identical call within one
 * investigation (or across a few seconds of chat traffic) hits Kafka / OpenSearch once instead of
 * each time. Used through {@link CachedTools}.
 * <p>
 * Error results (the tools return {@code "Error..."} strings instead of throwing) and exceptions
 * are never cached. Identical concurrent calls are single-flighted: the first one runs, the others
 * wait for its result — Embabel's PARALLEL tool loop makes that case common. The wait is lock-free
 * (a future), so it does not pin a virtual thread.
 */
@Component
public class ToolResultCache {

    private static final Logger log = LoggerFactory.getLogger(ToolResultCache.class);
    private static final String ERROR_PREFIX = "Error";

    private record Entry(Tool.Result result, long expiresAtNanos) {
        boolean fresh() {
            return System.nanoTime() - expiresAtNanos < 0;
        }
    }

    private final boolean enabled;
    private final Cache<String, Entry> cache;
    private final ConcurrentHashMap<String, CompletableFuture<Tool.Result>> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public ToolResultCache(@Value("${agent.cache.tools.enabled:true}") boolean enabled,
                           @Value("${agent.cache.tools.max-size:500}") long maxSize) {
        this.enabled = enabled;
        this.cache = Caffeine.newBuilder().maximumSize(maxSize).build();
    }

    public Tool.Result getOrLoad(String key, Duration ttl, Supplier<Tool.Result> load) {
        if (!enabled) {
            return load.get();
        }
        Entry cached = cache.getIfPresent(key);
        if (cached != null && cached.fresh()) {
            hits.incrementAndGet();
            log.debug("Tool cache HIT {}", key);
            return cached.result();
        }

        CompletableFuture<Tool.Result> mine = new CompletableFuture<>();
        CompletableFuture<Tool.Result> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            return awaitLeader(running, load);
        }
        return loadAsLeader(key, ttl, load, mine);
    }

    private Tool.Result awaitLeader(CompletableFuture<Tool.Result> leader, Supplier<Tool.Result> load) {
        try {
            Tool.Result shared = leader.get();
            hits.incrementAndGet();
            return shared;
        } catch (ExecutionException e) {
            // The leader failed — don't inherit its failure, just run it ourselves.
            return load.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for an identical tool call", e);
        }
    }

    private Tool.Result loadAsLeader(String key, Duration ttl, Supplier<Tool.Result> load,
                                     CompletableFuture<Tool.Result> mine) {
        misses.incrementAndGet();
        try {
            Tool.Result result = load.get();
            if (isCacheable(result)) {
                cache.put(key, new Entry(result, System.nanoTime() + ttl.toNanos()));
            }
            mine.complete(result);
            return result;
        } catch (Throwable t) {
            mine.completeExceptionally(t);
            throw t;
        } finally {
            inFlight.remove(key);
        }
    }

    private static boolean isCacheable(Tool.Result result) {
        if (!(result instanceof Tool.Result.Text text)) {
            return false;
        }
        String content = text.getContent();
        return !content.isBlank() && !content.stripLeading().regionMatches(true, 0, ERROR_PREFIX, 0, ERROR_PREFIX.length());
    }

    public long hits() {
        return hits.get();
    }

    public long misses() {
        return misses.get();
    }
}
