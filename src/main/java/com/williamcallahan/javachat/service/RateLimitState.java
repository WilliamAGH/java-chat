package com.williamcallahan.javachat.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Persistent rate limit state manager that survives application restarts.
 * Tracks actual rate limit windows and implements intelligent backoff.
 */
@Component
public class RateLimitState {
    private static final Logger log = LoggerFactory.getLogger(RateLimitState.class);
    private static final String STATE_FILE = "./data/rate-limit-state.json";
    
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    private Map<String, ProviderState> providerStates = new ConcurrentHashMap<>();
    
    public RateLimitState() {
        this.objectMapper = new ObjectMapper();
        // Register JavaTimeModule to handle Java 8 time types
        this.objectMapper.registerModule(new JavaTimeModule());
        // Configure to write timestamps as ISO-8601 strings instead of numbers
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    
    @PostConstruct
    public void init() {
        loadState();
        // Periodically save state every 5 minutes
        scheduler.scheduleAtFixedRate(this::saveState, 5, 5, TimeUnit.MINUTES);
        log.info("RateLimitState initialized with persistent storage at: {}", STATE_FILE);
    }
    
    @PreDestroy
    public void shutdown() {
        saveState();
        scheduler.shutdown();
    }
    
    /**
     * Record a rate limit hit with proper backoff calculation
     */
    public void recordRateLimit(String provider, Instant resetTime, String rateLimitWindow) {
        ProviderState state = providerStates.computeIfAbsent(provider, k -> new ProviderState());
        
        // Parse rate limit window (e.g., "24h", "1d", "6h")
        Duration windowDuration = parseRateLimitWindow(rateLimitWindow);
        
        // If we don't have a reset time from headers, calculate based on window
        if (resetTime == null) {
            resetTime = Instant.now().plus(windowDuration);
        }
        
        state.rateLimitedUntil = resetTime;
        state.consecutiveFailures++;
        state.lastFailure = Instant.now();
        
        // Implement exponential backoff for repeated failures
        if (state.consecutiveFailures > 1) {
            Duration additionalBackoff = Duration.ofHours((long) Math.pow(2, state.consecutiveFailures - 1));
            Duration maxBackoff = Duration.ofDays(7); // Never back off more than a week
            
            if (additionalBackoff.compareTo(maxBackoff) > 0) {
                additionalBackoff = maxBackoff;
            }
            
            state.rateLimitedUntil = state.rateLimitedUntil.plus(additionalBackoff);
            log.warn("Provider {} has {} consecutive failures. Extended backoff until: {}", 
                provider, state.consecutiveFailures, state.rateLimitedUntil);
        }
        
        saveState();
        log.info("Provider {} rate limited until: {} (window: {})", provider, resetTime, rateLimitWindow);
    }
    
    /**
     * Record a successful API call
     */
    public void recordSuccess(String provider) {
        ProviderState state = providerStates.computeIfAbsent(provider, k -> new ProviderState());
        state.consecutiveFailures = 0;
        state.lastSuccess = Instant.now();
        state.totalSuccesses++;
    }
    
    /**
     * Check if a provider is currently available
     */
    public boolean isAvailable(String provider) {
        ProviderState state = providerStates.get(provider);
        if (state == null) {
            return true;
        }
        
        if (state.rateLimitedUntil != null && Instant.now().isBefore(state.rateLimitedUntil)) {
            return false;
        }
        
        // Clear rate limit if it has expired
        if (state.rateLimitedUntil != null && Instant.now().isAfter(state.rateLimitedUntil)) {
            state.rateLimitedUntil = null;
            state.consecutiveFailures = 0;
            saveState();
        }
        
        return true;
    }
    
    /**
     * Get remaining wait time for a provider
     */
    public Duration getRemainingWaitTime(String provider) {
        ProviderState state = providerStates.get(provider);
        if (state == null || state.rateLimitedUntil == null) {
            return Duration.ZERO;
        }
        
        Duration remaining = Duration.between(Instant.now(), state.rateLimitedUntil);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }
    
    /**
     * Parse rate limit window strings like "24h", "1d", "6h"
     */
    private Duration parseRateLimitWindow(String window) {
        if (window == null || window.isEmpty()) {
            return Duration.ofHours(1); // Default to 1 hour
        }
        
        window = window.toLowerCase().trim();
        
        try {
            if (window.endsWith("d")) {
                int days = Integer.parseInt(window.substring(0, window.length() - 1));
                return Duration.ofDays(days);
            } else if (window.endsWith("h")) {
                int hours = Integer.parseInt(window.substring(0, window.length() - 1));
                return Duration.ofHours(hours);
            } else if (window.endsWith("m")) {
                int minutes = Integer.parseInt(window.substring(0, window.length() - 1));
                return Duration.ofMinutes(minutes);
            } else {
                // Try to parse as hours by default
                return Duration.ofHours(Long.parseLong(window));
            }
        } catch (Exception e) {
            log.warn("Failed to parse rate limit window '{}', using 1 hour default", window, e);
            return Duration.ofHours(1);
        }
    }
    
    private void loadState() {
        File file = new File(STATE_FILE);
        if (file.exists()) {
            try {
                StateData data = objectMapper.readValue(file, StateData.class);
                if (data != null && data.providers != null) {
                    providerStates = new ConcurrentHashMap<>(data.providers);
                    log.info("Loaded rate limit state for {} providers", providerStates.size());
                    
                    // Log current state
                    for (Map.Entry<String, ProviderState> entry : providerStates.entrySet()) {
                        if (!isAvailable(entry.getKey())) {
                            Duration remaining = getRemainingWaitTime(entry.getKey());
                            log.warn("Provider {} is rate limited for {} more", 
                                entry.getKey(), formatDuration(remaining));
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to load rate limit state, starting fresh", e);
            }
        }
    }
    
    private void saveState() {
        File file = new File(STATE_FILE);
        file.getParentFile().mkdirs();
        
        try {
            StateData data = new StateData();
            data.providers = new ConcurrentHashMap<>(providerStates);
            data.savedAt = Instant.now();
            
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, data);
        } catch (IOException e) {
            log.error("Failed to save rate limit state", e);
        }
    }
    
    private String formatDuration(Duration duration) {
        long days = duration.toDays();
        long hours = duration.toHours() % 24;
        long minutes = duration.toMinutes() % 60;
        
        if (days > 0) {
            return String.format("%dd %dh %dm", days, hours, minutes);
        } else if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else {
            return String.format("%dm", minutes);
        }
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StateData {
        public Map<String, ProviderState> providers;
        public Instant savedAt;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProviderState {
        public Instant rateLimitedUntil;
        public Instant lastSuccess;
        public Instant lastFailure;
        public int consecutiveFailures;
        public long totalSuccesses;
        public long totalFailures;
    }
}