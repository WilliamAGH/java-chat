package com.williamcallahan.javachat.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Enhanced rate limit manager with persistent state and intelligent backoff.
 * Integrates with RateLimitState for persistence across restarts.
 */
@Component
public class RateLimitManager {

    private static final Logger log = LoggerFactory.getLogger(
        RateLimitManager.class
    );

    /**
     * Encapsulates rate limit information parsed from HTTP headers or error messages.
     * Eliminates data clump where resetTime and retrySeconds travel together.
     */
    private record ParsedRateLimitInfo(
        Instant resetTime,
        long retryAfterSeconds
    ) {
        static ParsedRateLimitInfo empty() {
            return new ParsedRateLimitInfo(null, 0);
        }

        boolean hasResetTime() {
            return resetTime != null;
        }
    }

    private final RateLimitState rateLimitState;
    private final Map<String, ApiEndpointState> endpointStates =
        new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> dailyUsage =
        new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> resetTimes =
        new ConcurrentHashMap<>();
    private final Environment env;

    public enum ApiProvider {
        OPENAI("openai", 500, "24h"),
        GITHUB_MODELS("github_models", 150, "24h"),
        LOCAL("local", Integer.MAX_VALUE, null);

        private final String name;
        private final int dailyLimit;
        private final String typicalRateLimitWindow;

        ApiProvider(
            String name,
            int dailyLimit,
            String typicalRateLimitWindow
        ) {
            this.name = name;
            this.dailyLimit = dailyLimit;
            this.typicalRateLimitWindow = typicalRateLimitWindow;
        }

        public String getName() {
            return name;
        }

        public String getTypicalRateLimitWindow() {
            return typicalRateLimitWindow;
        }
    }

    public static class ApiEndpointState {

        // Testing live refresh functionality
        private volatile boolean circuitOpen = false;
        private volatile Instant nextRetryTime;

        /**
         * Tracks consecutive failures for future circuit breaker implementation.
         * Currently incremented but not used for decision making.
         * Future enhancement: After N consecutive failures, apply longer backoff periods
         * or temporarily disable the provider to prevent cascading failures.
         */
        @SuppressWarnings("unused") // Reserved for future circuit breaker logic
        private volatile int consecutiveFailures = 0;

        private volatile int backoffMultiplier = 1;
        private final AtomicInteger requestsToday = new AtomicInteger(0);
        private volatile Instant dayReset = Instant.now().plus(
            Duration.ofDays(1)
        );

        public boolean isAvailable() {
            if (circuitOpen && Instant.now().isBefore(nextRetryTime)) {
                return false;
            }
            if (circuitOpen && Instant.now().isAfter(nextRetryTime)) {
                circuitOpen = false;
                backoffMultiplier = 1;
            }
            return true;
        }

        public void recordSuccess() {
            consecutiveFailures = 0;
            backoffMultiplier = 1;
            circuitOpen = false;
            requestsToday.incrementAndGet();
        }

        public void recordRateLimit(long retryAfterSeconds) {
            consecutiveFailures++;
            circuitOpen = true;

            if (retryAfterSeconds > 0) {
                nextRetryTime = Instant.now().plusSeconds(retryAfterSeconds);
            } else {
                // Exponential backoff for unknown retry times
                backoffMultiplier = Math.min(backoffMultiplier * 2, 32);
                nextRetryTime = Instant.now().plusSeconds(backoffMultiplier);
            }
        }

        public int getRequestsToday() {
            if (Instant.now().isAfter(dayReset)) {
                requestsToday.set(0);
                dayReset = Instant.now().plus(Duration.ofDays(1));
            }
            return requestsToday.get();
        }
    }

    public RateLimitManager(RateLimitState rateLimitState, Environment env) {
        this.rateLimitState = rateLimitState;
        this.env = env;
        log.info("RateLimitManager initialized with persistent state");
    }

    private boolean isProviderConfigured(ApiProvider provider) {
        // Skip providers that are not configured to avoid noisy failures
        return switch (provider) {
            case OPENAI -> hasText(env.getProperty("OPENAI_API_KEY"));
            case GITHUB_MODELS -> hasText(env.getProperty("GITHUB_TOKEN"));
            case LOCAL -> true;
        };
    }

    private boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    public boolean isProviderAvailable(ApiProvider provider) {
        // First check if provider is actually configured
        if (!isProviderConfigured(provider)) {
            log.debug("Provider {} not configured; treating as unavailable", provider.getName());
            return false;
        }

        // Then check persistent rate limit state
        if (!rateLimitState.isAvailable(provider.getName())) {
            Duration remaining = rateLimitState.getRemainingWaitTime(
                provider.getName()
            );
            if (!remaining.isZero()) {
                log.debug(
                    "Provider {} is rate limited for {} (persistent state)",
                    provider.getName(),
                    formatDuration(remaining)
                );
                return false;
            }
        }

        // Then check in-memory circuit breaker state
        ApiEndpointState state = endpointStates.computeIfAbsent(
            provider.getName(),
            k -> new ApiEndpointState()
        );

        if (!state.isAvailable()) {
            log.debug(
                "Provider {} is in circuit breaker state until {}",
                provider.getName(),
                state.nextRetryTime
            );
            return false;
        }

        if (state.getRequestsToday() >= provider.dailyLimit) {
            log.warn(
                "Provider {} has reached daily limit of {} requests",
                provider.getName(),
                provider.dailyLimit
            );
            return false;
        }

        return true;
    }

    public void recordSuccess(ApiProvider provider) {
        // Update both in-memory and persistent state
        ApiEndpointState state = endpointStates.computeIfAbsent(
            provider.getName(),
            k -> new ApiEndpointState()
        );
        state.recordSuccess();
        rateLimitState.recordSuccess(provider.getName());

        log.debug(
            "Provider {} request successful. Daily usage: {}/{}",
            provider.getName(),
            state.getRequestsToday(),
            provider.dailyLimit
        );
    }

    public void recordRateLimit(ApiProvider provider, String errorMessage) {
        // Extract reset time from error or headers
        Instant resetTime = parseResetTimeFromError(errorMessage);
        long retryAfterSeconds = extractRetryAfter(errorMessage);

        // For GitHub Models, use longer backoff as they have strict limits
        if (provider == ApiProvider.GITHUB_MODELS) {
            if (resetTime == null && retryAfterSeconds == 0) {
                // GitHub typically has 24-hour rate limits
                resetTime = Instant.now().plus(Duration.ofHours(24));
                log.info(
                    "GitHub Models rate limited - applying 24-hour backoff"
                );
            }
        }

        // Update in-memory state
        ApiEndpointState state = endpointStates.computeIfAbsent(
            provider.getName(),
            k -> new ApiEndpointState()
        );
        state.recordRateLimit(retryAfterSeconds);

        // Update persistent state with proper window
        rateLimitState.recordRateLimit(
            provider.getName(),
            resetTime,
            provider.getTypicalRateLimitWindow()
        );

        log.warn(
            "Provider {} rate limited. Reset time: {}, Retry after: {} seconds",
            provider.getName(),
            resetTime != null ? resetTime : state.nextRetryTime,
            retryAfterSeconds
        );
    }

    /**
     * Parse rate limit reset time from WebClientResponseException
     */
    public void recordRateLimitFromException(
        ApiProvider provider,
        Throwable error
    ) {
        if (error instanceof WebClientResponseException webError) {
            ParsedRateLimitInfo info = parseRateLimitHeaders(webError);

            if (info.hasResetTime()) {
                applyRateLimitWithResetTime(provider, info.resetTime());
            } else {
                recordRateLimit(provider, webError.getMessage());
            }
        } else {
            recordRateLimit(provider, error.getMessage());
        }
    }

    /**
     * Parse rate limit information from HTTP response headers.
     */
    private ParsedRateLimitInfo parseRateLimitHeaders(
        WebClientResponseException webError
    ) {
        Instant resetTime = parseResetHeader(
            webError.getHeaders().getFirst("X-RateLimit-Reset")
        );
        long retrySeconds = parseRetryAfterHeader(
            webError.getHeaders().getFirst("Retry-After")
        );

        // If we have retry seconds but no reset time, compute reset time from retry
        if (resetTime == null && retrySeconds > 0) {
            resetTime = Instant.now().plusSeconds(retrySeconds);
        }

        return new ParsedRateLimitInfo(resetTime, retrySeconds);
    }

    /**
     * Parse the X-RateLimit-Reset header (epoch seconds or ISO instant).
     */
    private Instant parseResetHeader(String resetHeader) {
        if (resetHeader == null) {
            return null;
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(resetHeader));
        } catch (NumberFormatException e) {
            try {
                return Instant.parse(resetHeader);
            } catch (Exception ex) {
                log.debug(
                    "Could not parse rate limit reset header: {}",
                    resetHeader
                );
                return null;
            }
        }
    }

    /**
     * Parse the Retry-After header (seconds).
     */
    private long parseRetryAfterHeader(String retryAfter) {
        if (retryAfter == null) {
            return 0;
        }
        try {
            return Long.parseLong(retryAfter);
        } catch (NumberFormatException e) {
            log.debug("Could not parse Retry-After header: {}", retryAfter);
            return 0;
        }
    }

    /**
     * Apply rate limit when we have a specific reset time from headers.
     * Updates both persistent state and in-memory circuit breaker.
     */
    private void applyRateLimitWithResetTime(
        ApiProvider provider,
        Instant resetTime
    ) {
        // Update persistent state
        rateLimitState.recordRateLimit(
            provider.getName(),
            resetTime,
            provider.getTypicalRateLimitWindow()
        );

        // Update in-memory circuit breaker state
        ApiEndpointState state = endpointStates.computeIfAbsent(
            provider.getName(),
            k -> new ApiEndpointState()
        );
        long secondsUntilReset = Math.max(
            0,
            Duration.between(Instant.now(), resetTime).getSeconds()
        );
        state.recordRateLimit(secondsUntilReset);
    }

    private Instant parseResetTimeFromError(String errorMessage) {
        // Try to parse reset time from error message
        if (errorMessage != null && errorMessage.contains("reset")) {
            // Implementation would parse various formats
            // For now, return null and rely on window-based calculation
        }
        return null;
    }

    private long extractRetryAfter(String errorMessage) {
        if (errorMessage == null) return 0;

        try {
            if (errorMessage.contains("Please wait")) {
                String[] parts = errorMessage.split("Please wait ");
                if (parts.length > 1) {
                    String secondsPart = parts[1].split(" seconds")[0].trim();
                    return Long.parseLong(secondsPart);
                }
            }

            if (errorMessage.contains("retry-after")) {
                String[] parts = errorMessage.split("retry-after[: ]+");
                if (parts.length > 1) {
                    String secondsPart = parts[1].replaceAll("[^0-9]", "");
                    if (!secondsPart.isEmpty()) {
                        return Long.parseLong(secondsPart);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract retry-after from error message", e);
        }

        return 0;
    }

    public ApiProvider selectBestProvider() {
        // Priority order: OpenAI > GitHub Models > Local
        for (ApiProvider provider : new ApiProvider[] {
            ApiProvider.OPENAI,
            ApiProvider.GITHUB_MODELS,
            ApiProvider.LOCAL,
        }) {
            if (!isProviderConfigured(provider)) {
                log.debug(
                    "Skipping provider {}: not configured",
                    provider.getName()
                );
                continue;
            }
            if (isProviderAvailable(provider)) {
                log.debug("Selected provider: {}", provider.getName());
                return provider;
            }
        }

        // Log detailed status for debugging
        for (ApiProvider provider : ApiProvider.values()) {
            if (!isProviderConfigured(provider)) {
                log.warn(
                    "Provider {} unavailable - missing configuration (API key/token)",
                    provider.getName()
                );
                continue;
            }
            Duration remaining = rateLimitState.getRemainingWaitTime(
                provider.getName()
            );
            if (!remaining.isZero()) {
                log.warn(
                    "Provider {} unavailable - rate limited for {}",
                    provider.getName(),
                    formatDuration(remaining)
                );
            }
        }

        return null;
    }

    public void reset() {
        endpointStates.clear();
        dailyUsage.clear();
        resetTimes.clear();
        log.info("Rate limit manager reset (in-memory state only)");
    }

    private String formatDuration(Duration duration) {
        if (duration.isNegative()) {
            return "0s";
        }

        long days = duration.toDays();
        long hours = duration.toHours() % 24;
        long minutes = duration.toMinutes() % 60;
        long seconds = duration.getSeconds() % 60;

        if (days > 0) {
            return String.format("%dd %dh %dm", days, hours, minutes);
        } else if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }
}
