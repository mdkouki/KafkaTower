package com.lynra.kafkatower.config;

/**
 * Restarts the Spring context inside the running JVM so that settings read only at startup (the
 * LLM configuration, see {@link LlmSettingsEnvironmentPostProcessor}) take effect without
 * restarting the process.
 */
public interface ApplicationRestarter {

    /** Schedules the restart and returns immediately, so the caller can still send its response. */
    void restart();
}
