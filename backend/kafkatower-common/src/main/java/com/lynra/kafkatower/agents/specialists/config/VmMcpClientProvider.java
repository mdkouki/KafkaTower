package com.lynra.kafkatower.agents.specialists.config;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.progressive.UnfoldingTool;
import com.embabel.agent.spi.support.springai.SpringAiMcpToolFactory;
import com.embabel.agent.tools.mcp.McpToolFactory;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Owns the VictoriaMetrics MCP client connection.
 * <p>
 * Two transports are supported, selected by {@code agent.mcp.vm-transport}:
 * <ul>
 *   <li>{@code sse} (default) — legacy HTTP+SSE ({@code GET /sse} + {@code POST /message}).
 *       The session lives only as long as the SSE stream: behind a load balancer without
 *       session affinity, or a proxy that buffers/kills SSE, every call fails with
 *       {@code "Invalid session ID"}.</li>
 *   <li>{@code streamable} — streamable HTTP ({@code POST /mcp}). The session id travels in
 *       the {@code Mcp-Session-Id} header on ordinary requests, so it survives connection
 *       drops and is far more tolerant of proxies/LBs. Preferred whenever the server
 *       exposes {@code /mcp}.</li>
 * </ul>
 * The MCP Java SDK lazily opens a session on the first call and never recovers once the
 * server stops recognizing it. This provider holds the current {@link McpSyncClient} and
 * exposes {@link #reconnect()}, which discards the stale client/transport and lazily
 * re-initializes a fresh session on next use. {@link McpHealthChecker} calls
 * {@link #reconnect()} when a probe fails.
 */
@Component
public class VmMcpClientProvider {

    private static final Logger log = LoggerFactory.getLogger(VmMcpClientProvider.class);

    private final String vmUrl;
    private final String vmToken;
    private final String vmTenant;
    private final String vmTransport;

    private volatile McpSyncClient client;
    // Rebuilt together with `client` (constructor, reconnect()) so toolFactory() is a plain
    // field read instead of allocating a new SpringAiMcpToolFactory — and re-deriving the
    // ~16-tool list from MCP callbacks — on every call.
    private volatile McpToolFactory toolFactory;

    public VmMcpClientProvider(
            @Value("${agent.mcp.vm-url}") String vmUrl,
            @Value("${agent.mcp.vm-token:}") String vmToken,
            @Value("${agent.mcp.vm-tenant:}") String vmTenant,
            @Value("${agent.mcp.vm-transport:sse}") String vmTransport) {
        this.vmUrl = vmUrl;
        this.vmToken = vmToken;
        this.vmTenant = vmTenant;
        this.vmTransport = vmTransport;
        if (!"sse".equalsIgnoreCase(vmTransport) && !"streamable".equalsIgnoreCase(vmTransport)) {
            log.warn("Unknown agent.mcp.vm-transport '{}' — falling back to 'sse'", vmTransport);
        }
        this.client = buildClient();
        this.toolFactory = new SpringAiMcpToolFactory(List.of(this.client));
    }

    private McpClientTransport buildTransport() {
        if ("streamable".equalsIgnoreCase(vmTransport)) {
            var builder = HttpClientStreamableHttpTransport.builder(vmUrl)
                    .endpoint("/mcp");
            if (!vmToken.isBlank() || !vmTenant.isBlank()) {
                builder.httpRequestCustomizer((req, method, uri, body, context) -> {
                    if (!vmToken.isBlank()) {
                        req.header("Authorization", "Bearer " + vmToken);
                    }
                    if (!vmTenant.isBlank()) {
                        req.header("X-Scope-OrgID", vmTenant);
                    }
                });
            }
            return builder.build();
        }

        var builder = HttpClientSseClientTransport.builder(vmUrl)
                .sseEndpoint("/sse");
        if (!vmToken.isBlank() || !vmTenant.isBlank()) {
            builder.httpRequestCustomizer((req, method, uri, body, context) -> {
                if (!vmToken.isBlank()) {
                    req.header("Authorization", "Bearer " + vmToken);
                }
                if (!vmTenant.isBlank()) {
                    req.header("X-Scope-OrgID", vmTenant);
                }
            });
        }
        return builder.build();
    }

    private McpSyncClient buildClient() {
        log.info("Building VictoriaMetrics MCP client: transport={}, url={}",
                "streamable".equalsIgnoreCase(vmTransport) ? "streamable(/mcp)" : "sse(/sse)", vmUrl);
        return McpClient.sync(buildTransport())
                .requestTimeout(Duration.ofSeconds(30))
                .build();
    }

    /** The current client. The underlying SSE session is opened lazily on first use. */
    public McpSyncClient client() {
        return client;
    }

    /**
     * Discards the current connection and lazily opens a fresh SSE session on next use.
     * <p>
     * {@code client} is shared by every concurrent request. Two guards keep concurrent
     * health-check failures from breaking each other's in-flight calls:
     * <ul>
     *   <li>the caller passes the client it observed as stale ({@code reconnect(McpSyncClient)});
     *       if another thread already rebuilt it, this call is a no-op that returns the
     *       already-fresh client instead of rebuilding (and closing) a second time;</li>
     *   <li>the stale client isn't closed synchronously — another request may still be mid-call
     *       on it — it's closed on a short delay instead, after in-flight calls have had a
     *       chance to finish.</li>
     * </ul>
     */
    public synchronized McpSyncClient reconnect(McpSyncClient stale) {
        if (client != stale) {
            // Someone else already reconnected since the caller observed `stale`.
            return client;
        }
        McpSyncClient old = client;
        client = buildClient();
        toolFactory = new SpringAiMcpToolFactory(List.of(client));
        log.info("Reconnected VictoriaMetrics MCP client (new SSE session)");
        CompletableFuture.runAsync(() -> {
            try {
                old.closeGracefully();
            } catch (Exception e) {
                log.debug("Error closing stale VictoriaMetrics MCP client: {}", e.getMessage());
            }
        }, CompletableFuture.delayedExecutor(60, TimeUnit.SECONDS));
        return client;
    }

    /** The {@link McpToolFactory} backed by the current client. Rebuilt together with it on reconnect. */
    public McpToolFactory toolFactory() {
        return toolFactory;
    }

    /**
     * Runs {@code action} against the current client/tool state; if the result signals failure
     * per {@code shouldRetry}, reconnects with a fresh SSE session and retries exactly once.
     * <p>
     * This is the one place that knows the "probe, reconnect on failure, retry once" policy for
     * a session that can silently go stale (see class Javadoc — {@code "Invalid session ID"}).
     * Both {@link McpHealthChecker} (probing with {@code listTools()}) and
     * {@link ReconnectingMcpTool} (retrying an actual tool call that came back as
     * {@link Tool.Result.Error}) go through this instead of each re-implementing the same
     * reconnect/retry sequence against {@link #reconnect(McpSyncClient)} independently.
     */
    public <T> T withReconnectRetry(Supplier<T> action, Predicate<T> shouldRetry) {
        return withReconnectRetry(action, action, shouldRetry);
    }

    /**
     * Same as {@link #withReconnectRetry(Supplier, Predicate)}, but lets the retry attempt use
     * a different supplier than the first — e.g. re-resolving a tool reference bound to the now-
     * stale client, rather than paying that re-resolution cost on every successful call.
     */
    public <T> T withReconnectRetry(Supplier<T> action, Supplier<T> onRetry, Predicate<T> shouldRetry) {
        McpSyncClient before = client();
        T result = action.get();
        if (shouldRetry.test(result)) {
            log.info("MCP call failed — reconnecting with a fresh SSE session and retrying once");
            reconnect(before);
            result = onRetry.get();
        }
        return result;
    }

    /**
     * Same as {@code toolFactory().unfolding(name, description, tc -> true)}, except every
     * inner tool is wrapped in {@link ReconnectingMcpTool}, which retries once via
     * {@link #withReconnectRetry} instead of surfacing a raw protocol error to the LLM as if
     * it were real tool data.
     * <p>
     * {@code childToolUsageNotes} is appended to the "Tools now available: ..." message
     * returned when the facade is invoked — the only point where per-tool workflow guidance
     * (which of the ~16 revealed VictoriaMetrics tools to call first, and with what) reaches
     * the LLM at the moment it has actually committed to using this tool group. Without it,
     * models have been observed re-invoking the bare facade (which is always replaced by a
     * same-named guide tool, so the repeat call succeeds and returns the same listing) instead
     * of ever calling a real inner tool. Build it with {@link #standardChildUsageNotes}.
     */
    public UnfoldingTool unfoldingWithRetry(String name, String description, String childToolUsageNotes) {
        UnfoldingTool raw = toolFactory().unfolding(name, description, tc -> true);
        List<Tool> resilient = raw.getInnerTools().stream()
                .<Tool>map(t -> new ReconnectingMcpTool(t.getDefinition().getName(), t, this))
                .toList();
        return UnfoldingTool.of(name, description, resilient, true, childToolUsageNotes);
    }

    /**
     * Shared skeleton for a facade's {@code childToolUsageNotes}: the "call the real tool
     * directly, don't re-invoke the facade" instruction and the {@code cluster="<name>"}
     * requirement are identical for every VictoriaMetrics facade — only {@code workflowClause}
     * (which tool to call first, and why) varies per caller.
     */
    public static String standardChildUsageNotes(String facadeName, String workflowClause) {
        return "Call one of the revealed tools directly by name — do NOT call " + facadeName
                + " again. " + workflowClause + " Every query MUST include cluster=\"<name>\".";
    }

    @jakarta.annotation.PreDestroy
    void close() {
        try {
            client.closeGracefully();
        } catch (Exception e) {
            log.debug("Error closing VictoriaMetrics MCP client on shutdown: {}", e.getMessage());
        }
    }
}
