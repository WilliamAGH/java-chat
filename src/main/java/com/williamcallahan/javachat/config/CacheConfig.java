package com.williamcallahan.javachat.config;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Configures application caches and eviction scheduling.
 */
@Configuration
@EnableCaching
@EnableScheduling
public class CacheConfig {
    private static final String RERANKER_CACHE = "reranker-cache";
    private static final String ENRICHMENT_CACHE = "enrichment-cache";
    private static final String CHAT_CACHE = "chat-cache";
    private static final long EVICT_INTERVAL = 300_000L;

    /**
     * Creates cache configuration.
     */
    public CacheConfig() {}

    /**
     * Registers the cache manager with configured cache names.
     *
     * @return cache manager
     */
    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager(RERANKER_CACHE, ENRICHMENT_CACHE, CHAT_CACHE);
    }

    /**
     * Evicts all caches at a fixed interval.
     */
    @Scheduled(fixedRate = EVICT_INTERVAL)
    public void evictAllCachesAtIntervals() {
        final CacheManager manager = cacheManager();
        manager.getCacheNames().forEach(cacheName -> {
            final Cache cache = manager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        });
    }
}
