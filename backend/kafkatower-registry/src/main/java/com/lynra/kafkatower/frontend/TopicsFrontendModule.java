package com.lynra.kafkatower.frontend;

import com.lynra.kafkatower.agents.core.specialist.FrontendModule;
import org.springframework.stereotype.Component;

/**
 * Declares the live topic registry (per-cluster topic properties, config, ACL producers/consumers,
 * and consumer-group lag) as a menu entry. The Svelte page it points to lives under key
 * {@code "topics"} in {@code frontend/src/modules/pages.js}.
 */
@Component
public class TopicsFrontendModule implements FrontendModule {

    @Override
    public String key() {
        return "topics";
    }

    @Override
    public String menuLabel() {
        return "Topics";
    }
}
