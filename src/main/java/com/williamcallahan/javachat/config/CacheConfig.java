package com.williamcallahan.javachat.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableCaching
@EnableScheduling
public class CacheConfig {
    
    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager(
            "reranker-cache",
            "enrichment-cache",
            "chat-cache"
        );
    }
    
    @Scheduled(fixedRate = 300000)
    public void evictAllCachesAtIntervals() {
        cacheManager().getCacheNames()
            .forEach(cacheName -> cacheManager().getCache(cacheName).clear());
    }
}