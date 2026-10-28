package com.lynra.kafkatower;

import com.lynra.kafkatower.config.ApplicationRestarter;
import io.opentelemetry.api.GlobalOpenTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Closes the running Spring context and starts a fresh one in the same JVM, so settings read only
 * at startup (see {@code LlmSettingsEnvironmentPostProcessor}) take effect without restarting the
 * process. The web server is down for the few seconds in between; in-flight requests, in-memory
 * chat history and HTTP sessions do not survive.
 */
@Component
class SpringContextRestarter implements ApplicationRestarter {

    private static final Logger log = LoggerFactory.getLogger(SpringContextRestarter.class);
    /** Gives the HTTP response that triggered the restart time to reach the client. */
    private static final long RESPONSE_GRACE_MS = 500;

    private final ConfigurableApplicationContext context;
    private final ApplicationArguments arguments;
    private final AtomicBoolean restarting = new AtomicBoolean();

    SpringContextRestarter(ConfigurableApplicationContext context, ApplicationArguments arguments) {
        this.context = context;
        this.arguments = arguments;
    }

    @Override
    public void restart() {
        if (!restarting.compareAndSet(false, true)) {
            return;
        }
        // Not a daemon thread: once the context closes there are no other non-daemon threads, and
        // the JVM would exit before the new context has started.
        Thread restarter = new Thread(this::doRestart, "context-restarter");
        restarter.setDaemon(false);
        restarter.start();
    }

    private void doRestart() {
        try {
            Thread.sleep(RESPONSE_GRACE_MS);
            log.info("Restarting the application context");
            context.close();
            // Embabel's observability registers the OpenTelemetry SDK globally, and the API
            // refuses a second registration — which the new context would attempt.
            GlobalOpenTelemetry.resetForTest();
            relaunch(arguments.getSourceArgs());
        } catch (Exception e) {
            log.error("Context restart failed — retrying once with the assistant disabled so the console stays up", e);
            try {
                GlobalOpenTelemetry.resetForTest();
                String[] safe = Arrays.copyOf(arguments.getSourceArgs(), arguments.getSourceArgs().length + 1);
                safe[safe.length - 1] = "--kafkatower.assistant.enabled=false";
                relaunch(safe);
            } catch (Exception again) {
                log.error("Safe-mode restart failed too — the application needs to be restarted manually", again);
            }
        }
    }

    private static void relaunch(String[] args) {
        SpringApplication.run(KafkaTowerApplication.class, args);
    }
}
