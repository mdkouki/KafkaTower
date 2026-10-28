package com.lynra.kafkatower.agents.specialists.config;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.progressive.UnfoldingTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link MetricsBackend} backed by a Prometheus-compatible HTTP API, configured under
 * {@code agent.metrics.*}. Works unchanged against VictoriaMetrics (single node, or the cluster
 * version with its {@code /select/<accountID>/prometheus} prefix in the URL), Prometheus,
 * Thanos and Mimir.
 */
@Component
public class PromQlMetricsBackend implements MetricsBackend {

    private final PromQlQueryTools queryTools;

    public PromQlMetricsBackend(
            @Value("${agent.metrics.url}") String url,
            @Value("${agent.metrics.token:}") String token,
            @Value("${agent.metrics.username:}") String username,
            @Value("${agent.metrics.password:}") String password,
            @Value("${agent.metrics.tenant:}") String tenant) {
        this.queryTools = new PromQlQueryTools(url, token, username, password, tenant);
    }

    @Override
    public boolean isAvailable() {
        return queryTools.ping();
    }

    @Override
    public String unavailableMessage() {
        return "The metrics service is currently unreachable.";
    }

    @Override
    public UnfoldingTool queryTools(String facadeName, String purpose, String workflowClause) {
        List<Tool> tools = Tool.fromInstance(queryTools);
        return UnfoldingTool.of(facadeName, facadeDescription(purpose), tools, true,
                MetricsBackend.standardChildUsageNotes(facadeName, workflowClause));
    }
}
