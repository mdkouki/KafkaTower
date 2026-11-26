package com.lynra.kafkatower.utils;

import com.embabel.agent.api.tool.DelegatingTool;
import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.ToolCallContext;

import java.time.Duration;

/** Serves a tool's results from {@link ToolResultCache}; the tool's name, schema and metadata are unchanged. */
final class CachingTool implements DelegatingTool {

    private static final char KEY_SEPARATOR = '\u0000';

    private final Tool delegate;
    private final ToolResultCache cache;
    private final Duration ttl;
    private final String keyPrefix;

    CachingTool(Tool delegate, String owner, ToolResultCache cache, Duration ttl) {
        this.delegate = delegate;
        this.cache = cache;
        this.ttl = ttl;
        this.keyPrefix = owner + KEY_SEPARATOR + delegate.getDefinition().getName() + KEY_SEPARATOR;
    }

    @Override
    public Tool getDelegate() {
        return delegate;
    }

    @Override
    public Definition getDefinition() {
        return delegate.getDefinition();
    }

    @Override
    public Metadata getMetadata() {
        return delegate.getMetadata();
    }

    @Override
    public Result call(String input) {
        return cache.getOrLoad(keyPrefix + input, ttl, () -> delegate.call(input));
    }

    @Override
    public Result call(String input, ToolCallContext context) {
        return cache.getOrLoad(keyPrefix + input, ttl, () -> delegate.call(input, context));
    }
}
