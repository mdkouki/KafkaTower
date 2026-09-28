package com.lynra.kafkatower.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder().recordStats());

        // consumer-groups list: TTL of 1 hour
        manager.registerCustomCache("consumer-groups",
                Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.HOURS).recordStats().build());

        return manager;
    }
}