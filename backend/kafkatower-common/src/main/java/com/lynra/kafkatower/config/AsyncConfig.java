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

    // Runs the analytics classification LLM call, which takes seconds — kept off
    // promptAnalyticsExecutor so slow LLM calls can't delay the quick DB writes queued there.
    // Bounded queue + default AbortPolicy: when saturated the caller drops the classification
    // (analytics only) rather than ever running it on, or blocking, the chat request thread.
    @Bean
    public Executor promptClassificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("prompt-classification-");
        executor.initialize();
        return executor;
    }
}
