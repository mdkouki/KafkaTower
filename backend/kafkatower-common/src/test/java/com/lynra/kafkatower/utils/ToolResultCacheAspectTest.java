package com.lynra.kafkatower.utils;

import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultCacheAspectTest {

    static class FakeTools {
        final AtomicInteger calls = new AtomicInteger();
        volatile String next = "ok";
        volatile CountDownLatch gate;

        @CachedTool(ttlSeconds = 30)
        public String read(String cluster, String topic) {
            calls.incrementAndGet();
            if (gate != null) {
                try { gate.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            }
            return next + ":" + cluster + "/" + topic;
        }

        @CachedTool(ttlSeconds = 30)
        public String boom(String x) {
            calls.incrementAndGet();
            throw new IllegalStateException("kaboom");
        }

        @CachedTool(ttlSeconds = 0)
        public String instantlyStale(String x) {
            calls.incrementAndGet();
            return "v" + calls.get();
        }
    }

    private FakeTools proxy(FakeTools target, boolean enabled, ToolResultCacheAspect[] out) {
        ToolResultCacheAspect aspect = new ToolResultCacheAspect(enabled, 100);
        if (out != null) out[0] = aspect;
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    @Test
    void secondIdenticalCallIsServedFromCache() {
        FakeTools target = new FakeTools();
        ToolResultCacheAspect[] aspect = new ToolResultCacheAspect[1];
        FakeTools tools = proxy(target, true, aspect);

        assertThat(tools.read("c1", "t")).isEqualTo("ok:c1/t");
        target.next = "changed";
        assertThat(tools.read("c1", "t")).isEqualTo("ok:c1/t");

        assertThat(target.calls.get()).isEqualTo(1);
        assertThat(aspect[0].hits()).isEqualTo(1);
        assertThat(aspect[0].misses()).isEqualTo(1);
    }

    @Test
    void differentArgumentsAreDifferentEntries() {
        FakeTools target = new FakeTools();
        FakeTools tools = proxy(target, true, null);

        tools.read("c1", "t");
        tools.read("c2", "t");
        tools.read("c1", "other");

        assertThat(target.calls.get()).isEqualTo(3);
    }

    @Test
    void errorResultsAreNeverCached() {
        FakeTools target = new FakeTools();
        FakeTools tools = proxy(target, true, null);
        target.next = "Error: broker did not respond";

        tools.read("c1", "t");
        target.next = "recovered";

        assertThat(tools.read("c1", "t")).isEqualTo("recovered:c1/t");
        assertThat(target.calls.get()).isEqualTo(2);
    }

    @Test
    void exceptionsPropagateAndAreNeverCached() {
        FakeTools target = new FakeTools();
        FakeTools tools = proxy(target, true, null);

        for (int i = 0; i < 2; i++) {
            try {
                tools.boom("x");
            } catch (IllegalStateException expected) {
                assertThat(expected).hasMessage("kaboom");
            }
        }

        assertThat(target.calls.get()).isEqualTo(2);
    }

    @Test
    void expiredEntryIsRefetched() {
        FakeTools target = new FakeTools();
        FakeTools tools = proxy(target, true, null);

        assertThat(tools.instantlyStale("x")).isEqualTo("v1");
        assertThat(tools.instantlyStale("x")).isEqualTo("v2");
    }

    @Test
    void disabledMeansNoCaching() {
        FakeTools target = new FakeTools();
        FakeTools tools = proxy(target, false, null);

        tools.read("c1", "t");
        tools.read("c1", "t");

        assertThat(target.calls.get()).isEqualTo(2);
    }

    @Test
    void concurrentIdenticalCallsRunTheToolOnce() throws Exception {
        FakeTools target = new FakeTools();
        target.gate = new CountDownLatch(1);
        FakeTools tools = proxy(target, true, null);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> a = pool.submit(() -> tools.read("c1", "t"));
            // Wait until the leader is inside the tool, then start the follower.
            while (target.calls.get() == 0) Thread.sleep(5);
            Future<String> b = pool.submit(() -> tools.read("c1", "t"));
            Thread.sleep(100);
            target.gate.countDown();

            assertThat(a.get()).isEqualTo("ok:c1/t");
            assertThat(b.get()).isEqualTo("ok:c1/t");
        }
        assertThat(target.calls.get()).isEqualTo(1);
    }
}
