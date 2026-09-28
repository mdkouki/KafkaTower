package com.lynra.kafkatower.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables {@code @Scheduled} jobs for this module ({@code AclGraphRefreshJob}, {@code TopicGraphRefreshJob}). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
