package com.lynra.kafkatower.agents.core.specialist;

/*
 * Kept separate from Specialist: Specialist<Q, A> is shaped around one Embabel agent's
 * question/answer contract, unrelated to whether a module also ships a frontend page.
 */
public interface FrontendModule {

    /** Must match the key the frontend registers the page component under (frontend/src/lib/pages.js). */
    String key();

    String menuLabel();
}
