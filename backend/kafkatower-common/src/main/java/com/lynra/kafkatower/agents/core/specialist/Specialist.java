package com.lynra.kafkatower.agents.core.specialist;

/**
 * Common shape implemented by every Kafka specialist sub-agent (KafkaInvestigator,
 * KafkaLogInspector, KafkaMetricsInspector), so the root orchestrator can hold them as
 * {@code List<Specialist<?, ?>>} instead of hardcoding one string constant and one
 * question/answer wiring per specialist.
 * <p>
 * {@code name()} must match the {@code @Agent} name Embabel deploys this specialist under —
 * that's the key the orchestrator uses to look up the runnable Embabel {@code Agent} from the
 * {@code AgentPlatform} once it has resolved which {@code Specialist} to invoke.
 */
public interface Specialist<Q, A> {

    String name();

    Class<A> answerType();

    Q question(String text);

    String text(A answer);

    /**
     * Appends cross-specialist behavioral guidance (tool-output handling, query strategy, reply
     * format, etc. — see {@code skills/shared/*.md} in kafkatower-common) onto this specialist's
     * system prompt. Each specialist owns its own domain skill doc and loads that itself; this
     * shared, cross-cutting layer is deliberately assembled and pushed in from kafkatower-app
     * instead — the orchestration layer is what decides how specialists are allowed to behave,
     * not each specialist module individually. Called once, after every Specialist bean exists
     * (see the app-module injector for the exact timing).
     */
    void applySharedGuidance(String sharedGuidance);
}
