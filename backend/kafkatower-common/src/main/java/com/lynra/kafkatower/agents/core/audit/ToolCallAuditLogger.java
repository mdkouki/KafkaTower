package com.lynra.kafkatower.agents.core.audit;

import com.embabel.agent.api.tool.callback.AfterToolCallContext;
import com.embabel.agent.api.tool.callback.BeforeToolCallContext;
import com.embabel.agent.api.tool.callback.ToolCallInspector;
import com.lynra.kafkatower.utils.PromptSecurityValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs every tool call an agent makes: which tool, with what arguments, how long it took,
 * and its result. Attach via {@code PromptRunner.withToolCallInspectors(INSTANCE)} to any
 * agent whose tools touch live Kafka/OpenSearch/metrics state, for an audit trail.
 */
public final class ToolCallAuditLogger implements ToolCallInspector {

    public static final ToolCallAuditLogger INSTANCE = new ToolCallAuditLogger();

    private static final Logger log = LoggerFactory.getLogger(ToolCallAuditLogger.class);
    private static final int MAX_LOGGED_CHARS = 300;

    private ToolCallAuditLogger() {
    }

    @Override
    public void beforeToolCall(BeforeToolCallContext context) {
        log.info("tool-call tool={} args={}",
                context.getToolCall().getName(), truncate(context.getToolCall().getArguments()));
    }

    @Override
    public void afterToolCall(AfterToolCallContext context) {
        String result = context.getResultAsString();
        log.info("tool-result tool={} durationMs={} result={}",
                context.getToolCall().getName(), context.getDurationMs(), truncate(result));

        // Tool output (topic/group/principal names, broker config values, log message bodies)
        // originates from live cluster/registry state, not from this conversation — anyone who
        // can write one of those values can plant text phrased as an instruction, and it flows
        // straight into the LLM's context. PromptSecurityValidator only guards the user's own
        // message; this is the corresponding check on the other side of the tool boundary. It
        // deliberately only logs — the relevant skills instruct the model to treat tool output
        // as data, not instructions, so this is a detection signal for security review, not a
        // block (blocking here would also break on legitimate Kafka resource names that happen
        // to trip the same broad heuristics).
        if (PromptSecurityValidator.isThreat(result)) {
            log.warn("SECURITY: tool result for tool={} contains a prompt-injection-like pattern — " +
                            "review as a possible indirect injection via cluster/registry data: {}",
                    context.getToolCall().getName(), PromptSecurityValidator.sanitize(result));
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_LOGGED_CHARS ? value : value.substring(0, MAX_LOGGED_CHARS) + "...[truncated]";
    }
}
