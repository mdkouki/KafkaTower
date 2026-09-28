package com.lynra.kafkatower.agents.core.audit;

import com.embabel.agent.api.tool.callback.AfterToolCallContext;
import com.embabel.agent.api.tool.callback.BeforeToolCallContext;
import com.embabel.agent.api.tool.callback.ToolCallInspector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Collects every specialist tool call and its result made during a single
 * {@code KafkaInspector.answer()} invocation, so the completeness reviewer can check the draft
 * against what specialists actually returned, and {@code EnrichedSubAgentTools} can seed a new
 * specialist call with what earlier ones already found. Create one instance per request — unlike
 * {@link ToolCallAuditLogger}, this is stateful and must not be shared across requests.
 */
public final class ToolCallEvidenceCollector implements ToolCallInspector {

    private static final int MAX_TRANSCRIPT_CHARS = 24_000;
    private static final String ENTRY_SEPARATOR = "\n\n---\n\n";

    /** Tool names whose calls/results are specialist findings worth forwarding to the next
     *  specialist call — as opposed to listClusters, etc. Must stay in
     *  sync with the {@code @LlmTool} method names on {@code EnrichedSubAgentTools}. */
    private static final Set<String> SPECIALIST_TOOL_NAMES = Set.of("askLogs", "askMetrics", "askInvestigation");

    // Synchronized because Embabel/Spring AI can dispatch multiple tool calls from a single LLM
    // turn concurrently.
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void beforeToolCall(BeforeToolCallContext context) {
        // no-op: only the result is needed as evidence
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

    /** Every tool call this request, capped and newest-first-preserved so it fits a completeness
     *  review prompt without ballooning across orchestration rounds. */
    public String transcript() {
        List<String> snapshot = snapshot();
        if (snapshot.isEmpty()) {
            return "(no specialist tools were called for this answer)";
        }
        return capped(snapshot, "[%d older tool call(s) elided]" + ENTRY_SEPARATOR);
    }

    /**
     * Every prior specialist ({@code askLogs}/{@code askMetrics}/{@code askInvestigation}) call
     * and result recorded so far this request, formatted for prepending onto a new specialist
     * call's question — or {@code null} if no specialist has been called yet. Unlike
     * {@link #transcript()} this excludes non-specialist tool calls (listClusters, etc.):
     * those aren't findings another specialist needs to reconcile with.
     */
    public String specialistFindingsSoFar() {
        List<String> specialistCalls = snapshot().stream()
                .filter(entry -> SPECIALIST_TOOL_NAMES.stream().anyMatch(name -> entry.startsWith("Tool: " + name + "\n")))
                .toList();
        if (specialistCalls.isEmpty()) {
            return null;
        }
        return capped(specialistCalls, "[%d older specialist call(s) elided]" + ENTRY_SEPARATOR);
    }

    /** Keeps the most recent entries — closest to whatever is being reviewed/asked next — up to
     *  {@link #MAX_TRANSCRIPT_CHARS}, dropping older ones from the front once the budget is hit. */
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
