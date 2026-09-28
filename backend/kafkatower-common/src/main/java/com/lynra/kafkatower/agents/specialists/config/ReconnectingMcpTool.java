package com.lynra.kafkatower.agents.specialists.config;

import com.embabel.agent.api.tool.DelegatingTool;
import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.ToolCallContext;
import com.lynra.kafkatower.utils.ToolResponseTruncator;

/**
 * Wraps a single VictoriaMetrics MCP tool so a call that fails because the SSE session went
 * stale (see {@link VmMcpClientProvider} Javadoc — "Invalid session ID") gets one reconnect +
 * retry, instead of leaking a raw protocol error into the LLM's context as if it were data.
 * <p>
 * {@link McpHealthChecker} does the same reconnect-on-failure for its own probe call; both go
 * through {@link VmMcpClientProvider#withReconnectRetry} rather than each hand-rolling the
 * reconnect/retry sequence. Since {@code toolFactory()} is rebuilt together with the client on
 * reconnect, re-resolving the tool by name after a retry picks up the fresh session for free.
 * <p>
 * Also truncates oversized results (see {@link #truncateIfNeeded}) via {@link ToolResponseTruncator}
 * — MCP tool calls (e.g. {@code query_range} over a wide window, or a broad {@code metrics}
 * discovery pattern) never pass through a Spring-managed {@code @LlmTool} bean method, so
 * {@code ToolResponseTruncator}'s AOP `@Around` on that annotation can't intercept them; this
 * calls the same cap/log/notice mechanics directly as the equivalent backstop for the MCP path.
 */
final class ReconnectingMcpTool implements DelegatingTool {

    private static final String TRUNCATION_ADVICE = "Narrow your query — use an instant query "
            + "instead of a range, add a coarser step, a shorter time window, or a more specific "
            + "series filter.";

    private final String toolName;
    private final VmMcpClientProvider provider;
    private volatile Tool delegate;

    ReconnectingMcpTool(String toolName, Tool initialDelegate, VmMcpClientProvider provider) {
        this.toolName = toolName;
        this.delegate = initialDelegate;
        this.provider = provider;
    }

    @Override
    public Tool getDelegate() {
        return delegate;
    }

    @Override
    public Tool.Definition getDefinition() {
        return delegate.getDefinition();
    }

    @Override
    public Tool.Result call(String input, ToolCallContext context) {
        Tool.Result result = provider.withReconnectRetry(
                () -> delegate.call(input, context),
                () -> resolveFreshDelegate().call(input, context),
                r -> r instanceof Tool.Result.Error);
        return truncateIfNeeded(result);
    }

    /**
     * Caps text content via {@link ToolResponseTruncator#truncateIfNeeded(String, String, String)},
     * the same limit and mechanics used for every other tool path. Errors are passed through
     * untouched.
     */
    private Tool.Result truncateIfNeeded(Tool.Result result) {
        String content = switch (result) {
            case Tool.Result.Text text -> text.getContent();
            case Tool.Result.WithArtifact withArtifact -> withArtifact.getContent();
            default -> null;
        };
        if (content == null || content.length() <= ToolResponseTruncator.MAX_CHARS) {
            return result;
        }
        String truncated = ToolResponseTruncator.truncateIfNeeded(toolName, content, TRUNCATION_ADVICE);
        return (result instanceof Tool.Result.WithArtifact withArtifact)
                ? Tool.Result.withArtifact(truncated, withArtifact.getArtifact())
                : Tool.Result.text(truncated);
    }

    /** Re-resolves this tool by name against the (now-reconnected) provider, only on retry. */
    private Tool resolveFreshDelegate() {
        Tool fresh = provider.toolFactory().toolByName(toolName);
        if (fresh != null) {
            delegate = fresh;
        }
        return delegate;
    }
}
