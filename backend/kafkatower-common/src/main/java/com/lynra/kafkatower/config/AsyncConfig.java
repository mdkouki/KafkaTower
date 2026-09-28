package com.lynra.kafkatower.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    // Dedicated, small pool for fire-and-forget prompt-analytics writes only — kept separate
    // from any future general-purpose executor so a burst of chat traffic can't starve or be
    // starved by unrelated async work.
    @Bean
    public Executor promptAnalyticsExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("prompt-analytics-");
        executor.initialize();
        return executor;
    }
}
