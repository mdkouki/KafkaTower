package com.lynra.kafkatower.agents.specialists.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class McpHealthChecker {

    private static final Logger log = LoggerFactory.getLogger(McpHealthChecker.class);

    private final VmMcpClientProvider clientProvider;

    public McpHealthChecker(VmMcpClientProvider clientProvider) {
        this.clientProvider = clientProvider;
    }

    /**
     * Returns true if the MCP server is reachable and exposes tools.
     * Probes with a listTools() call — fast, ~1 round-trip.
     * <p>
     * If the probe fails (e.g. the SSE session expired after a long idle period,
     * surfacing as "Invalid session ID"), {@link VmMcpClientProvider#withReconnectRetry}
     * reconnects with a fresh SSE session and retries once before giving up.
     */
    public boolean isAvailable() {
        return clientProvider.withReconnectRetry(this::probe, available -> !available);
    }

    private boolean probe() {
        try {
            var result = clientProvider.client().listTools();
            return result != null && !result.tools().isEmpty();
        } catch (Exception e) {
            log.warn("MCP client health check failed: {}", e.getMessage());
            return false;
        }
    }

    public String unavailableMessage() {
        return """
                The VictoriaMetrics metrics service (vm-mcp) is currently unreachable.
                """;
    }
}
