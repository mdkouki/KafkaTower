package com.lynra.kafkatower.utils;

import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.api.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CachedToolsTest {

    static class FakeTools {
        final AtomicInteger calls = new AtomicInteger();
        volatile String next = "ok";
        volatile CountDownLatch gate;

        @CachedTool(ttlSeconds = 30)
        @LlmTool(description = "reads a topic")
        public String read(String cluster, String topic) {
            calls.incrementAndGet();
            if (gate != null) {
                try { gate.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            }
            return next + ":" + cluster + "/" + topic;
        }

        @CachedTool(ttlSeconds = 30)
        @LlmTool(name = "renamed_tool", description = "has an explicit tool name")
        public String named(String x) {
            calls.incrementAndGet();
            return "named:" + x;
        }

        @CachedTool(ttlSeconds = 0)
        @LlmTool(description = "is stale as soon as it is cached")
        public String instantlyStale(String x) {
            calls.incrementAndGet();
            return "v" + calls.get();
        }

        @LlmTool(description = "never cached")
        public String uncached(String x) {
            calls.incrementAndGet();
            return "u" + calls.get();
        }
    }

    private static String json(String... keyValues) {
        StringBuilder text = new StringBuilder("{");
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) text.append(',');
            text.append('"').append(keyValues[i]).append("\":\"").append(keyValues[i + 1]).append('"');
        }
        return text.append('}').toString();
    }

    private static String content(Tool.Result result) {
        assertThat(result).isInstanceOf(Tool.Result.Text.class);
        return ((Tool.Result.Text) result).getContent();
    }

    private static Tool toolNamed(List<Tool> tools, String name) {
        return tools.stream().filter(tool -> tool.getDefinition().getName().equals(name)).findFirst().orElseThrow();
    }

    private static Tool read(FakeTools target, ToolResultCache cache) {
        return toolNamed(new CachedTools(cache).from(target), "read");
    }

    private static String readCall(String cluster, String topic) {
        return json("cluster", cluster, "topic", topic);
    }

    @Test
    void secondIdenticalCallIsServedFromCache() {
        FakeTools target = new FakeTools();
        ToolResultCache cache = new ToolResultCache(true, 100);
        Tool tool = read(target, cache);

        assertThat(content(tool.call(readCall("c1", "t")))).isEqualTo("ok:c1/t");
        target.next = "changed";
        assertThat(content(tool.call(readCall("c1", "t")))).isEqualTo("ok:c1/t");

        assertThat(target.calls.get()).isEqualTo(1);
        assertThat(cache.hits()).isEqualTo(1);
        assertThat(cache.misses()).isEqualTo(1);
    }

    @Test
    void differentArgumentsAreDifferentEntries() {
        FakeTools target = new FakeTools();
        Tool tool = read(target, new ToolResultCache(true, 100));

        tool.call(readCall("c1", "t"));
        tool.call(readCall("c2", "t"));
        tool.call(readCall("c1", "other"));

        assertThat(target.calls.get()).isEqualTo(3);
    }

    @Test
    void errorResultsAreNeverCached() {
        FakeTools target = new FakeTools();
        Tool tool = read(target, new ToolResultCache(true, 100));
        target.next = "Error: broker did not respond";

        tool.call(readCall("c1", "t"));
        target.next = "recovered";

        assertThat(content(tool.call(readCall("c1", "t")))).isEqualTo("recovered:c1/t");
        assertThat(target.calls.get()).isEqualTo(2);
    }

    @Test
    void expiredEntryIsRefetched() {
        FakeTools target = new FakeTools();
        Tool tool = toolNamed(new CachedTools(new ToolResultCache(true, 100)).from(target), "instantlyStale");

        assertThat(content(tool.call(json("x", "1")))).isEqualTo("v1");
        assertThat(content(tool.call(json("x", "1")))).isEqualTo("v2");
    }

    @Test
    void disabledMeansNoCaching() {
        FakeTools target = new FakeTools();
        Tool tool = read(target, new ToolResultCache(false, 100));

        tool.call(readCall("c1", "t"));
        tool.call(readCall("c1", "t"));

        assertThat(target.calls.get()).isEqualTo(2);
    }

    @Test
    void toolsWithoutTheAnnotationAreLeftAlone() {
        FakeTools target = new FakeTools();
        Tool tool = toolNamed(new CachedTools(new ToolResultCache(true, 100)).from(target), "uncached");

        tool.call(json("x", "1"));
        tool.call(json("x", "1"));

        assertThat(target.calls.get()).isEqualTo(2);
    }

    @Test
    void anExplicitToolNameIsTheOneThatIsCached() {
        FakeTools target = new FakeTools();
        Tool tool = toolNamed(new CachedTools(new ToolResultCache(true, 100)).from(target), "renamed_tool");

        tool.call(json("x", "1"));
        tool.call(json("x", "1"));

        assertThat(target.calls.get()).isEqualTo(1);
    }

    @Test
    @Timeout(10)
    void concurrentIdenticalCallsRunTheToolOnce() throws Exception {
        FakeTools target = new FakeTools();
        target.gate = new CountDownLatch(1);
        Tool tool = read(target, new ToolResultCache(true, 100));

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Tool.Result> leader = pool.submit(() -> tool.call(readCall("c1", "t")));
            while (target.calls.get() == 0) {
                if (leader.isDone()) leader.get();
                Thread.sleep(5);
            }
            Future<Tool.Result> follower = pool.submit(() -> tool.call(readCall("c1", "t")));
            Thread.sleep(100);
            target.gate.countDown();

            assertThat(content(leader.get())).isEqualTo("ok:c1/t");
            assertThat(content(follower.get())).isEqualTo("ok:c1/t");
        }
        assertThat(target.calls.get()).isEqualTo(1);
    }
}
