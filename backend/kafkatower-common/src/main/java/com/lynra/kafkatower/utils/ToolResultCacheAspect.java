package com.lynra.kafkatower.utils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Short-TTL cache for read-only {@link CachedTool} tool methods, so a repeated or concurrent
 * identical call within one investigation (or across a few seconds of chat traffic) hits Kafka /
 * OpenSearch once instead of each time.
 * <p>
 * Runs <i>outside</i> {@link ToolResponseTruncator} (highest precedence), so what is cached is the
 * already-truncated result — smaller in memory, and identical to what the LLM would have seen.
 * Error results (the tools return {@code "Error..."} strings instead of throwing) and exceptions
 * are never cached. Identical concurrent calls are single-flighted: the first one runs, the others
 * wait for its result — Embabel's PARALLEL tool loop makes that case common. The wait is lock-free
 * (a future), so it does not pin a virtual thread.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ToolResultCacheAspect {

    private static final Logger log = LoggerFactory.getLogger(ToolResultCacheAspect.class);

    private record Entry(String value, long expiresAtNanos) {
        boolean fresh() {
            return System.nanoTime() - expiresAtNanos < 0;
        }
    }

    private final boolean enabled;
    private final Cache<String, Entry> cache;
    private final ConcurrentHashMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public ToolResultCacheAspect(@Value("${agent.cache.tools.enabled:true}") boolean enabled,
                                 @Value("${agent.cache.tools.max-size:500}") long maxSize) {
        this.enabled = enabled;
        this.cache = Caffeine.newBuilder().maximumSize(maxSize).build();
    }

    @Around("@annotation(cachedTool)")
    public Object cacheToolResult(ProceedingJoinPoint joinPoint, CachedTool cachedTool) throws Throwable {
        if (!enabled) {
            return joinPoint.proceed();
        }
        String key = joinPoint.getSignature().toLongString() + Arrays.deepToString(joinPoint.getArgs());

        Entry cached = cache.getIfPresent(key);
        if (cached != null && cached.fresh()) {
            hits.incrementAndGet();
            log.debug("Tool cache HIT {}", joinPoint.getSignature().getName());
            return cached.value();
        }

        CompletableFuture<String> mine = new CompletableFuture<>();
        CompletableFuture<String> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            try {
                String shared = running.get();
                hits.incrementAndGet();
                return shared;
            } catch (ExecutionException e) {
                // The leader failed — don't inherit its failure, just run it ourselves.
                return joinPoint.proceed();
            }
        }

        misses.incrementAndGet();
        try {
            Object result = joinPoint.proceed();
            if (result instanceof String text) {
                if (isCacheable(text)) {
                    cache.put(key, new Entry(text, System.nanoTime() + cachedTool.ttlSeconds() * 1_000_000_000L));
                }
                mine.complete(text);
            } else {
                mine.completeExceptionally(new IllegalStateException("not a String result"));
            }
            return result;
        } catch (Throwable t) {
            mine.completeExceptionally(t);
            throw t;
        } finally {
            inFlight.remove(key);
        }
    }

    private static boolean isCacheable(String text) {
        return !text.isBlank() && !text.stripLeading().regionMatches(true, 0, "Error", 0, 5);
    }

    public long hits() {
        return hits.get();
    }

    public long misses() {
        return misses.get();
    }
}
