package com.lynra.kafkatower.agents.core.specialist;

/*
 * name() must match the @Agent name Embabel deploys this specialist under; the orchestrator uses
 * it to look up the runnable Agent in the AgentPlatform.
 */
public interface Specialist<Q, A> {

    String name();

    String toolName();

    Class<A> answerType();

    Q question(String text);

    String text(A answer);

    /*
     * Cross-specialist guidance (skills/shared/*.md) is assembled and pushed in from kafkatower-app
     * rather than loaded per module, because the orchestration layer decides how specialists may
     * behave. Called once, after every Specialist bean exists.
     */
    void applySharedGuidance(String sharedGuidance);
}
