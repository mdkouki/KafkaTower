package com.lynra.kafkatower.agents.core.root;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.Ai;
import com.lynra.kafkatower.agents.core.audit.LlmCallTimer;
import com.lynra.kafkatower.agents.core.audit.ToolCallAuditLogger;
import com.lynra.kafkatower.agents.core.audit.ToolCallEvidenceCollector;
import com.lynra.kafkatower.agents.core.guardrails.NoSecretLeakageGuardRail;
import com.lynra.kafkatower.chatbot.PromptClassificationTool;
import com.lynra.kafkatower.utils.SkillLoader;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Agent(description = "Root Kafka assistant orchestrator. Analyzes every question to decide, " +
        "on its own, which specialist(s) to consult — KafkaMetricsInspector (historical metrics), KafkaInvestigator (live " +
        "state), or KafkaLogInspector (logs/traces) — calling one, several, or none of them, " +
        "in any order, and chaining their answers together when a question needs collaboration " +
        "(e.g. narrowing a timeframe via metrics before searching logs for an anomaly). Every " +
        "draft answer is independently reviewed for completeness before being returned, and " +
        "refined with further tool/specialist calls if it falls short.")
public class KafkaInspector {

    private static final Logger log = LoggerFactory.getLogger(KafkaInspector.class);

    /** Words shorter than this are dropped before comparing two reviewer reasons — filters connective/filler words ("the", "and", "with") that would otherwise dominate the overlap score regardless of actual topic. */
    private static final int MIN_SIGNIFICANT_WORD_LENGTH = 4;

    /** Overlap-coefficient threshold for "same gap as last round". Calibrated against two real
     *  Completeness.reason() values from the same conversation that flagged the same underlying
     *  fact (0.38) versus two unrelated reasons (0.125) — see KafkaInspectorTest. */
    private static final double GAP_OVERLAP_THRESHOLD = 0.35;

    private final PromptClassificationTool classificationTool;
    private final SubAgentTools subAgentTools;

    /** Draft -> reviewer -> refine, at most this many rounds. Every draft is reviewed, including the last. */
    private final int maxOrchestrationRounds;

    /** Any single LLM call (classification, draft, completeness-check) slower than this logs a warning. */
    private final long slowCallThresholdMs;

    private String analyticsPrompt;
    private String orchestrationPrompt;
    private String completenessCheckPrompt;

    public KafkaInspector(PromptClassificationTool classificationTool, SubAgentTools subAgentTools,
                           @Value("${agent.orchestration.max-rounds:2}") int maxOrchestrationRounds,
                           @Value("${agent.llm.slow-call-warning-ms:15000}") long slowCallThresholdMs) {
        this.classificationTool = classificationTool;
        this.subAgentTools = subAgentTools;
        this.maxOrchestrationRounds = maxOrchestrationRounds;
        this.slowCallThresholdMs = slowCallThresholdMs;
    }

    @PostConstruct
    void loadSkills() {
        analyticsPrompt = SkillLoader.loadContent("/skills/ROOT_ANALYTICS.md");
        orchestrationPrompt = SkillLoader.loadContent("/skills/ROOT_ORCHESTRATOR.md")
                + "\n\n" + SkillLoader.loadContent("/skills/shared/UNTRUSTED_TOOL_OUTPUT.md");
        completenessCheckPrompt = SkillLoader.loadContent("/skills/ROOT_COMPLETENESS_CHECK.md");
    }

    @AchievesGoal(description = "User question answered by the appropriate specialist or by collaboration between the list of specialists, with the draft reviewed for completeness before it is returned")
    @Action(description = "Analyze the Kafka question, orchestrate whichever specialist agent(s) are needed, and iterate if an independent review finds the draft incomplete")
    public AgentAnswer answer(UserMessage message, Ai ai) {
        ClassificationResult classification = LlmCallTimer.timed(log, "classification", slowCallThresholdMs, () ->
                ai.withAutoLlm()
                        .withSystemPrompt(analyticsPrompt)
                        .createObject(message.text(), ClassificationResult.class));
        classificationTool.classifyAndRecord(classification.theme(), classification.keywords(), classification.targetAgent());

        String question = message.text();
        String draft = null;
        String gap = null;
        String gapCategory = null;
        String evidenceSnapshot = null;
        ToolCallEvidenceCollector evidence = new ToolCallEvidenceCollector();
        // A per-request wrapper bound directly to `evidence` (not the raw singleton bean) so
        // specialist calls get seeded with prior specialist findings from this same request —
        // see EnrichedSubAgentTools's Javadoc for why this can't be a ThreadLocal instead.
        EnrichedSubAgentTools requestScopedTools = new EnrichedSubAgentTools(subAgentTools, evidence);

        for (int round = 1; round <= maxOrchestrationRounds; round++) {
            String prompt = (round == 1) ? question
                    : followUpPrompt(question, draft, gap, classification.theme(), evidenceSnapshot);

            // Non-streaming on purpose — nothing ever reached the browser token-by-token here
            // anyway: every round already ran inside TokenStreamSink.suppressed (the final
            // accepted draft is sent as a single chunk by ChatService's non-streamed fallback
            // once this method returns), so streaming bought no live-delivery benefit. Worse,
            // generateStream() exposes a flat Flux<String> with no turn-boundary markers, so
            // TokenStreamSink.collect() concatenated every chunk across the whole multi-turn
            // tool-calling loop into one string — including any stray narration a model emits
            // alongside a tool-call decision (e.g. "askInvestigation" prefixed onto the next
            // round's real answer). createObject() only returns the final turn's content.
            draft = LlmCallTimer.timed(log, "draft round " + round, slowCallThresholdMs, () ->
                    ai.withAutoLlm()
                            .withSystemPrompt(orchestrationPrompt)
                            .withToolObject(requestScopedTools)
                            .withToolCallInspectors(ToolCallAuditLogger.INSTANCE, evidence)
                            .withGuardRails(NoSecretLeakageGuardRail.INSTANCE)
                            .createObject(prompt, String.class));

            // Snapshot once per round and reuse below — both the completeness check and (on a
            // reject) next round's followUpPrompt need the same evidence, and nothing mutates
            // `evidence` again until the next draft call runs.
            evidenceSnapshot = evidence.transcript();

            // Review every round, including the last — an unreviewed final draft can silently
            // drop facts an earlier, reviewed draft had. We still stop retrying after
            // maxOrchestrationRounds; we just never return a draft nobody checked.
            // draft/evidenceSnapshot are reassigned each iteration, so they can't be captured
            // directly by the lambda below (not effectively final) — snapshot into final locals.
            final String draftForReview = draft;
            final String evidenceForReview = evidenceSnapshot;
            Completeness completeness = LlmCallTimer.timed(log, "completeness-check round " + round, slowCallThresholdMs, () ->
                    ai.withAutoLlm()
                            .withSystemPrompt(completenessCheckPrompt)
                            .createObject("User's question:\n" + question
                                    + "\n\nDraft answer:\n" + draftForReview
                                    + "\n\nEvidence actually returned by the specialists this round (the only "
                                    + "source the draft is allowed to state facts from):\n" + evidenceForReview,
                                    Completeness.class));
            if (completeness.sufficient()) {
                break;
            }
            if (round == maxOrchestrationRounds) {
                log.warn("KafkaInspector exhausted {} rounds still marked insufficient — returning it anyway. Last reviewer reason: {}",
                        maxOrchestrationRounds, completeness.reason());
                break;
            }
            // If the fix attempt didn't move the needle — the reviewer is flagging essentially
            // the same gap again — another round under the same instruction is very unlikely to
            // either. Stop now rather than spending a further round regenerating the same miss.
            if (isSameGap(gapCategory, gap, completeness.category(), completeness.reason())) {
                log.warn("KafkaInspector stopping early at round {} — reviewer repeated the same gap after a fix attempt: {}",
                        round, completeness.reason());
                break;
            }
            gap = completeness.reason();
            gapCategory = completeness.category();
        }

        // NOTE: whether the loop stopped because the draft was accepted or because it gave up
        // (the two log.warn cases above), the answer returned here is identical either way — the
        // caller has no way to tell "verified" from "gave up after N rounds still flagged" apart
        // from re-reading logs. Surfacing that distinction to the end user (e.g. a caveat on a
        // given-up answer) is a deliberate follow-up, not done here.
        return new AgentAnswer(draft);
    }

    /**
     * "Is the reviewer repeating last round's complaint" — gated on {@code category} first
     * (see ROOT_COMPLETENESS_CHECK.md) rather than judged by reason-text similarity alone: two
     * distinct fabrication complaints about different facts would otherwise share enough common
     * vocabulary ("draft", "evidence", "specialist") to look like a repeat, and category alone
     * would conflate two different missing-information gaps that happen to land in the same
     * round. Requiring both keeps false positives from either signal from tripping the other.
     */
    static boolean isSameGap(String previousCategory, String previousReason, String currentCategory, String currentReason) {
        if (previousReason == null || currentReason == null) return false;
        boolean sameCategory = previousCategory != null && !previousCategory.isBlank()
                && currentCategory != null && !currentCategory.isBlank()
                && previousCategory.trim().equalsIgnoreCase(currentCategory.trim());
        if (!sameCategory) return false;

        String a = normalizeForComparison(previousReason);
        String b = normalizeForComparison(currentReason);
        if (a.equals(b)) return true;

        Set<String> wordsA = significantWords(a);
        Set<String> wordsB = significantWords(b);
        if (wordsA.isEmpty() || wordsB.isEmpty()) return false;

        Set<String> intersection = new HashSet<>(wordsA);
        intersection.retainAll(wordsB);
        double overlap = (double) intersection.size() / Math.min(wordsA.size(), wordsB.size());
        return overlap >= GAP_OVERLAP_THRESHOLD;
    }

    private static String normalizeForComparison(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    private static Set<String> significantWords(String normalized) {
        Set<String> words = new HashSet<>(Arrays.asList(normalized.split(" ")));
        words.removeIf(w -> w.length() < MIN_SIGNIFICANT_WORD_LENGTH);
        return words;
    }

    private String followUpPrompt(String question, String priorDraft, String gap, String theme, String evidenceSoFar) {
        return """
                Original user question (about %s):
                %s

                Your previous draft answer:
                %s

                An independent reviewer judged this draft insufficient for this specific reason:
                %s

                Fix ONLY that gap. Treat the draft above as the starting point, not a first
                attempt to throw away — do not regenerate it from scratch, and do not change any
                sentence that isn't part of the problem the reviewer named. Make the smallest
                edit that closes the gap and leave everything else as it was.

                Every specialist tool call you (or an earlier round) already made this request,
                and its full result, is below — re-read it before doing anything else. Most gaps
                are answerable straight from this evidence (a fact you had but left out of the
                draft, or a wording/attribution problem) and need NO new tool call at all — in
                that case, correct only the flagged wording:
                %s

                Only call a specialist again if the gap names information that genuinely is not
                anywhere in the evidence above — and even then, ask a narrowly scoped follow-up
                question about just that missing piece, not the same broad question again (a
                fresh specialist call reruns its entire investigation from scratch, so repeating
                the original question wastes a full investigation on data you already have).

                Output the complete answer text (not a diff or a description of what you changed),
                with the gap closed and everything else preserved exactly as it was.
                """.formatted(theme, question, priorDraft, gap, evidenceSoFar);
    }
}
