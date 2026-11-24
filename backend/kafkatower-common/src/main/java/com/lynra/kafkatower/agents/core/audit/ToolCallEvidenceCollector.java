package com.lynra.kafkatower.agents.core.audit;

import com.embabel.agent.api.tool.callback.AfterToolCallContext;
import com.embabel.agent.api.tool.callback.BeforeToolCallContext;
import com.embabel.agent.api.tool.callback.ToolCallInspector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/*
 * Stateful: create one instance per request; unlike ToolCallAuditLogger it must not be shared.
 */
public final class ToolCallEvidenceCollector implements ToolCallInspector {

    private static final int MAX_TRANSCRIPT_CHARS = 24_000;
    private static final String ENTRY_SEPARATOR = "\n\n---\n\n";

    private final Set<String> specialistToolNames;

    // Synchronized because Embabel/Spring AI can dispatch multiple tool calls from a single LLM
    // turn concurrently.
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    public ToolCallEvidenceCollector(Set<String> specialistToolNames) {
        this.specialistToolNames = Set.copyOf(specialistToolNames);
    }

    @Override
    public void beforeToolCall(BeforeToolCallContext context) {
    }

    @Override
    public void afterToolCall(AfterToolCallContext context) {
        calls.add("Tool: " + context.getToolCall().getName()
                + "\nArgs: " + context.getToolCall().getArguments()
                + "\nResult: " + context.getResultAsString());
    }

    private List<String> snapshot() {
        synchronized (calls) {
            return new ArrayList<>(calls);
        }
    }

    public String transcript() {
        List<String> snapshot = snapshot();
        if (snapshot.isEmpty()) {
            return "(no specialist tools were called for this answer)";
        }
        return capped(snapshot, "[%d older tool call(s) elided]" + ENTRY_SEPARATOR);
    }

    public String specialistFindingsSoFar() {
        List<String> specialistCalls = snapshot().stream()
                .filter(entry -> specialistToolNames.stream().anyMatch(name -> entry.startsWith("Tool: " + name + "\n")))
                .toList();
        if (specialistCalls.isEmpty()) {
            return null;
        }
        return capped(specialistCalls, "[%d older specialist call(s) elided]" + ENTRY_SEPARATOR);
    }

    // Keeps the most recent entries, dropping older ones from the front once the budget is hit.
    private static String capped(List<String> entries, String elidedNoticeFormat) {
        List<String> kept = new ArrayList<>();
        int total = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            String entry = entries.get(i);
            if (total + entry.length() > MAX_TRANSCRIPT_CHARS && !kept.isEmpty()) {
                break;
            }
            kept.add(0, entry);
            total += entry.length();
        }
        String joined = String.join(ENTRY_SEPARATOR, kept);
        if (kept.size() < entries.size()) {
            joined = elidedNoticeFormat.formatted(entries.size() - kept.size()) + joined;
        }
        return joined;
    }
}
