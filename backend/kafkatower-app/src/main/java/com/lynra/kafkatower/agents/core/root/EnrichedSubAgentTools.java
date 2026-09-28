package com.lynra.kafkatower.agents.core.root;

import com.embabel.agent.api.annotation.LlmTool;
import com.lynra.kafkatower.agents.core.audit.ToolCallEvidenceCollector;

/**
 * Per-request wrapper around the {@link SubAgentTools} singleton that prepends prior specialist
 * findings onto each specialist call's question before delegating, so repeat or related calls
 * build on what earlier ones found instead of starting from a blank slate.
 * <p>
 * Constructed fresh per request and passed to {@code ai.withToolObject(...)} instead of the
 * singleton, so Embabel scans this object's {@code @LlmTool} methods instead. Deliberately a
 * plain, non-Spring object holding the evidence collector as a direct field rather than a
 * {@code ThreadLocal} or a second constructor on {@link SubAgentTools} — see
 * EMBABEL_GOAP_ALIGNMENT_PLAN.md for why both of those were tried first and rejected.
 */
public class EnrichedSubAgentTools {

    private final SubAgentTools delegate;
    private final ToolCallEvidenceCollector evidence;

    public EnrichedSubAgentTools(SubAgentTools delegate, ToolCallEvidenceCollector evidence) {
        this.delegate = delegate;
        this.evidence = evidence;
    }

    @LlmTool(description = """
            List every Kafka cluster this assistant can actually use (the live AdminClient
            connections needed by askInvestigation and every other specialist).

            ALWAYS call this before calling any ask* tool if you are not 100% certain of the exact
            cluster name — do not guess, invent, or assume a cluster name from the user's wording.
            Also call it if a specialist's answer says a cluster was "not found": that usually means
            the name was slightly wrong, not that no clusters exist.

            Parameter query: optional. Pass whatever cluster name/fragment the user mentioned (e.g.
            "prod", "the nonprod one") and matches are ranked by similarity — this resolves typos,
            partial names, and abbreviations (e.g. "prod" -> "nonprod-01") so you don't have to ask
            the user to repeat themselves or silently pick the wrong cluster. Leave empty (or pass
            an empty string) to just list every cluster.
            """)
    public String listClusters(String query) {
        return delegate.listClusters(query);
    }

    @LlmTool(description = """
            Ask the metrics specialist about HISTORICAL TIME-SERIES Kafka data: throughput/lag
            trends, capacity analysis, or comparisons over an explicit time window. Also use this
            to narrow down a vague or wide-open timeframe (e.g. find exactly when a lag spike or
            throughput drop started) before handing a precise window to another specialist.
            Parameter question: a fully self-contained question including any known time window.
            Findings from any specialist already called this conversation (including an earlier
            askMetrics call) are automatically prepended for you — no need to paste them in
            yourself. If those findings and this specialist's own result genuinely disagree, this
            specialist is expected to flag the disagreement, not silently pick one.
            """)
    public String askMetrics(String question) {
        return delegate.askMetrics(withPriorFindings(question));
    }

    @LlmTool(description = """
            Ask the live-investigation specialist about CURRENT Kafka state and root-cause
            analysis: consumer group health, partition assignments, active lag spikes, broker/ACL
            state. Use for troubleshooting happening right now, or to correlate live state with a
            metrics trend.
            Parameter question: a fully self-contained question. Findings from any specialist
            already called this conversation (including an earlier askInvestigation call) are
            automatically prepended for you — no need to paste them in yourself. This specialist
            independently discovers and queries metrics itself (Admin First, Metrics Second), so
            still mention the specific gap you need closed rather than repeating the original
            question verbatim. If the prepended findings and this specialist's own result
            genuinely disagree, this specialist is expected to flag the disagreement, not
            silently pick one.
            """)
    public String askInvestigation(String question) {
        return delegate.askInvestigation(withPriorFindings(question));
    }

    @LlmTool(description = """
            Ask the log specialist to search application/service logs and traces for exceptions,
            errors, or anomalies. Only available when OpenSearch is configured. Works best with a
            narrow, specific time window — if the user gave a large or open-ended timeframe, call
            askMetrics first to narrow it down to when the anomaly actually occurred.
            Parameter question: a fully self-contained question including the (ideally narrow)
            time window and any service/topic names to search for. Findings from any specialist
            already called this conversation (including an earlier askLogs call with the same or
            a related question) are automatically prepended for you. If a repeat search
            contradicts an earlier one, this specialist is expected to flag the disagreement
            explicitly rather than silently returning a different answer.
            """)
    public String askLogs(String question) {
        return delegate.askLogs(withPriorFindings(question));
    }

    /**
     * Prepends every specialist finding recorded so far this request onto {@code question},
     * including prior calls to the same specialist. Returns it unchanged if nothing's been
     * found yet. Package-private for direct unit testing.
     */
    String withPriorFindings(String question) {
        String findings = evidence.specialistFindingsSoFar();
        if (findings == null) {
            return question;
        }
        return """
                Findings from earlier specialist calls this conversation — confirm, refine, or, \
                if your own investigation genuinely disagrees, explicitly say so and why rather \
                than silently giving a contradicting answer with no acknowledgement:
                %s

                ---

                %s""".formatted(findings, question);
    }
}
