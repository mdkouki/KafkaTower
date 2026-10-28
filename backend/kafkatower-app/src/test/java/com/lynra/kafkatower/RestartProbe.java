package com.lynra.kafkatower;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Lets {@link ContextRestartTest} reach the context that {@code SpringContextRestarter} starts on
 * its own thread — the restarter has no other way to hand it out. Inert unless {@code restart.probe=true}.
 */
@Component
@ConditionalOnProperty(name = "restart.probe", havingValue = "true")
class RestartProbe {

    static volatile ConfigurableApplicationContext latest;

    private final ConfigurableApplicationContext context;

    RestartProbe(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @EventListener
    void onReady(ApplicationReadyEvent event) {
        latest = context;
    }
}
