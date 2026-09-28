package com.lynra.kafkatower.agents.core.specialist;

/**
 * Declares that a domain module contributes its own navigable page to the frontend shell.
 * <p>
 * Mirrors the {@link Specialist} discovery pattern: a module exposes a {@code @Component} bean
 * implementing this interface, kafkatower-app collects every {@code FrontendModule} via
 * {@code List<FrontendModule>} constructor injection and publishes them as the menu the frontend
 * shell renders. A module with no frontend (most {@link Specialist} implementations today)
 * simply has no {@code FrontendModule} bean — nothing to opt out of.
 * <p>
 * Kept separate from {@link Specialist} rather than folded into it: {@code Specialist<Q, A>} is
 * shaped around one Embabel agent's question/answer contract, which has nothing to do with
 * whether a module also ships a Svelte page. A module can implement both, either, or neither.
 */
public interface FrontendModule {

    /**
     * Stable identifier for this module's page. Must match the key the frontend registers the
     * page component under (see {@code frontend/src/lib/pages.js}).
     */
    String key();

    /**
     * Human-readable label the frontend shows in the menu.
     */
    String menuLabel();
}
