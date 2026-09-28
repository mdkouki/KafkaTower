package com.lynra.kafkatower.frontend;

import com.lynra.kafkatower.agents.core.specialist.FrontendModule;
import org.springframework.stereotype.Component;

/**
 * Declares the live ACL registry (per-cluster, per-user consume/produce topics, consumer
 * groups, and transactional IDs, computed from Kafka ACLs) as a menu entry. The Svelte page it
 * points to lives under key {@code "registry"} in {@code frontend/src/lib/pages.js}.
 */
@Component
public class RegistryFrontendModule implements FrontendModule {

    @Override
    public String key() {
        return "registry";
    }

    @Override
    public String menuLabel() {
        return "Registry";
    }
}
