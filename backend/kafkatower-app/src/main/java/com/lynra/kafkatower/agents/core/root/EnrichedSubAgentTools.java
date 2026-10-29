package com.lynra.kafkatower.agents.core.root;

import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.api.tool.Tool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynra.kafkatower.agents.core.audit.ToolCallEvidenceCollector;
import com.lynra.kafkatower.agents.core.specialist.Specialist;

import java.util.List;

/**
 * Per-request wrapper around the {@link SubAgentTools} singleton that prepends prior specialist
 * findings onto each specialist call's question before delegating, so repeat or related calls
 * build on what earlier ones found instead of starting from a blank slate.
 * <p>
 * Constructed fresh per request and passed to {@code ai.withToolObject(...)} instead of the
 * singleton, so Embabel scans this object's {@code @LlmTool} methods instead; the specialist
 * tools are added separately from {@link #specialistTools()}. Deliberately a
 * plain, non-Spring object holding the evidence collector as a direct field rather than a
 * {@code ThreadLocal} or a second constructor on {@link SubAgentTools} — see
 * EMBABEL_GOAP_ALIGNMENT_PLAN.md for why both of those were tried first and rejected.
 */
public class EnrichedSubAgentTools {

    private static final ObjectMapper JSON = new ObjectMapper();

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

    /**
     * One tool per specialist Spring deployed, named and described by the specialist itself
     * ({@link Specialist#toolName()} and its {@code @Agent} description). Pass these to
     * {@code withTools(...)} alongside this object: a specialist bean is all it takes to become
     * callable, and one that isn't deployed (e.g. no OpenSearch) is simply absent.
     */
    public List<Tool> specialistTools() {
        return delegate.specialists().stream()
                .map(this::toolFor)
                .toList();
    }

    private Tool toolFor(Specialist<?, ?> specialist) {
        return Tool.Companion.of(
                specialist.toolName(),
                delegate.toolDescription(specialist),
                Tool.InputSchema.of(Tool.Parameter.string("question", "Self-contained question for this specialist")),
                Tool.Metadata.DEFAULT,
                (Tool.Function) input -> Tool.Result.text(delegate.ask(specialist, withPriorFindings(questionFrom(input)))));
    }

    /** The tool input is the model's JSON arguments, {@code {"question": "..."}}; fall back to the raw text. */
    private static String questionFrom(String input) {
        try {
            return JSON.readTree(input).path("question").asText(input);
        } catch (JsonProcessingException e) {
            return input;
        }
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
