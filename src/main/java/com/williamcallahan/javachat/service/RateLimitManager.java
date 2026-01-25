package com.williamcallahan.javachat.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import com.openai.core.http.Headers;
import com.openai.errors.OpenAIServiceException;
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
        boolean hasResetTime() {
            return resetTime != null;
        }
    }

    private final RateLimitState rateLimitState;
    private final Map<String, ApiEndpointState> endpointStates =
        new ConcurrentHashMap<>();
    private final Environment env;
    private final RateLimitHeaderParser headerParser;

    /**
     * Describes supported LLM providers and their typical rate-limit characteristics.
     */
    public enum ApiProvider {
        /** OpenAI has short rate limit windows (typically seconds to minutes). */
        OPENAI("openai", 500, "1m"),
        /** GitHub Models has strict daily limits with 24-hour reset windows. */
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

        /**
         * Returns the provider identifier used for persistence keys and logging.
         */
        public String getName() {
            return name;
        }

        /**
         * Returns the typical rate-limit window description for the provider.
         */
        public String getTypicalRateLimitWindow() {
            return typicalRateLimitWindow;
        }
    }

    /**
     * Tracks in-memory availability state and backoff timing for a single provider.
     */
    public static class ApiEndpointState {

        private volatile boolean circuitOpen = false;
        private volatile Instant nextRetryTime;

        /**
         * Tracks consecutive failures for future circuit breaker implementation.
         * Currently incremented but not used for decision making.
         */
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

        private volatile int backoffMultiplier = 1;
        private final AtomicInteger requestsToday = new AtomicInteger(0);
        private volatile Instant dayReset = Instant.now().plus(
            Duration.ofDays(1)
        );

        /**
         * Reports whether the provider is eligible for requests based on the current circuit state.
         */
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

        /**
         * Records a successful request and clears any active backoff state.
         */
        public void recordSuccess() {
            consecutiveFailures.set(0);
            backoffMultiplier = 1;
            circuitOpen = false;
            requestsToday.incrementAndGet();
        }

        /**
         * Records a rate limit and computes the next retry time using explicit retry headers or exponential backoff.
         *
         * @param retryAfterSeconds seconds until retry when known
         */
        public void recordRateLimit(long retryAfterSeconds) {
            consecutiveFailures.incrementAndGet();
            circuitOpen = true;

            if (retryAfterSeconds > 0) {
                nextRetryTime = Instant.now().plusSeconds(retryAfterSeconds);
            } else {
                // Exponential backoff for unknown retry times
                backoffMultiplier = Math.min(backoffMultiplier * 2, 32);
                nextRetryTime = Instant.now().plusSeconds(backoffMultiplier);
            }
        }

        /**
         * Returns the number of requests counted for the current rolling day window.
         */
        public int getRequestsToday() {
            if (Instant.now().isAfter(dayReset)) {
                requestsToday.set(0);
                dayReset = Instant.now().plus(Duration.ofDays(1));
            }
            return requestsToday.get();
        }
    }

    /**
     * Builds a manager that combines in-memory circuit state with persisted rate-limit state.
     */
    public RateLimitManager(RateLimitState rateLimitState, Environment env) {
        this.rateLimitState = rateLimitState;
        this.env = env;
        this.headerParser = new RateLimitHeaderParser();
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

    private ApiEndpointState getOrCreateEndpointState(ApiProvider provider) {
        return endpointStates.computeIfAbsent(
            provider.getName(),
            providerKey -> new ApiEndpointState()
        );
    }

    private boolean hasText(String valueText) {
        return valueText != null && !valueText.trim().isEmpty();
    }

    /**
     * Reports whether the provider can be used right now based on configuration, persisted rate limits, and in-memory backoff.
     */
    public boolean isProviderAvailable(ApiProvider provider) {
        String providerName = sanitizeLogValue(provider.getName());
        // First check if provider is actually configured
        if (!isProviderConfigured(provider)) {
            log.debug("[{}] Not configured; treating as unavailable", providerName);
            return false;
        }

        // Then check persistent rate limit state
        if (!rateLimitState.isAvailable(provider.getName())) {
            Duration remaining = rateLimitState.getRemainingWaitTime(
                provider.getName()
            );
            if (!remaining.isZero()) {
                log.debug("[{}] Rate limited (persistent state)", providerName);
                return false;
            }
        }

        // Then check in-memory circuit breaker state
        ApiEndpointState state = getOrCreateEndpointState(provider);

        if (!state.isAvailable()) {
            log.debug("[{}] In circuit breaker state", providerName);
            return false;
        }

        if (state.getRequestsToday() >= provider.dailyLimit) {
            log.warn("[{}] Reached daily limit", providerName);
            return false;
        }

        return true;
    }

    /**
     * Records a successful provider request in both in-memory and persisted rate limit state.
     */
    public void recordSuccess(ApiProvider provider) {
        String providerName = sanitizeLogValue(provider.getName());
        // Update both in-memory and persistent state
        ApiEndpointState state = getOrCreateEndpointState(provider);
        state.recordSuccess();
        rateLimitState.recordSuccess(provider.getName());

        log.debug("[{}] Request successful", providerName);
    }

    /**
     * Records a rate limit for the provider using best-effort parsing of reset or retry information.
     */
    public void recordRateLimit(ApiProvider provider, String errorMessage) {
        String providerName = sanitizeLogValue(provider.getName());
        String safeErrorMessage = sanitizeLogValue(errorMessage);
        // Reset time parsing from error messages not implemented; rely on window-based fallback
        Instant resetTime = null;
        long retryAfterSeconds = extractRetryAfter(errorMessage);

        // For GitHub Models, use longer backoff as they have strict limits
        if (provider == ApiProvider.GITHUB_MODELS) {
            if (retryAfterSeconds == 0) {
                // GitHub typically has 24-hour rate limits
                resetTime = Instant.now().plus(Duration.ofHours(24));
                log.info("[{}] Rate limited - applying 24-hour backoff", providerName);
            }
        }

        // Update in-memory state
        ApiEndpointState state = getOrCreateEndpointState(provider);
        state.recordRateLimit(retryAfterSeconds);

        // Update persistent state with proper window
        rateLimitState.recordRateLimit(
            provider.getName(),
            resetTime,
            provider.getTypicalRateLimitWindow()
        );

        log.warn("[{}] Rate limited (errorMessage={})", providerName, safeErrorMessage);
    }

    /**
     * Records a rate limit using headers from the OpenAI Java SDK exception, if present.
     *
     * The SDK exposes response headers via {@link OpenAIServiceException#headers()}, which is more reliable than
     * parsing exception messages.
     *
     * @param provider the provider that produced the rate limit
     * @param exception OpenAI SDK service exception carrying response headers
     */
    public void recordRateLimitFromOpenAiServiceException(ApiProvider provider, OpenAIServiceException exception) {
        if (provider == null || exception == null) {
            return;
        }
        ParsedRateLimitInfo rateLimitInfo = parseRateLimitFromHeaders(exception.headers());
        if (rateLimitInfo.retryAfterSeconds > 0) {
            applyRateLimit(provider, Instant.now().plusSeconds(rateLimitInfo.retryAfterSeconds), rateLimitInfo.retryAfterSeconds);
            return;
        }

        if (rateLimitInfo.resetTime != null) {
            applyRateLimit(provider, rateLimitInfo.resetTime, 0);
            return;
        }

        // Fallback: no usable headers, use error-message-based heuristics.
        recordRateLimit(provider, exception.getMessage());
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
                applyRateLimit(provider, info.resetTime(), 0);
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
        Instant resetTime = headerParser.parseResetHeader(
            webError.getHeaders().getFirst("X-RateLimit-Reset")
        );
        long retrySeconds = headerParser.parseRetryAfterHeader(
            webError.getHeaders().getFirst("Retry-After")
        );

        // If we have retry seconds but no reset time, compute reset time from retry
        if (resetTime == null && retrySeconds > 0) {
            resetTime = Instant.now().plusSeconds(retrySeconds);
        }

        return new ParsedRateLimitInfo(resetTime, retrySeconds);
    }


    /**
     * Applies a rate limit using reset time and optional explicit retry-after seconds.
     * Updates both persistent state and in-memory circuit breaker, then logs the event.
     *
     * @param provider the rate-limited provider
     * @param resetTime the timestamp when the rate limit resets
     * @param retryAfterSecondsOverride explicit retry-after seconds (0 to compute from resetTime)
     */
    private void applyRateLimit(ApiProvider provider, Instant resetTime, long retryAfterSecondsOverride) {
        String providerName = sanitizeLogValue(provider.getName());
        ApiEndpointState state = getOrCreateEndpointState(provider);

        long retryAfterSeconds = retryAfterSecondsOverride > 0
            ? retryAfterSecondsOverride
            : Math.max(0, Duration.between(Instant.now(), resetTime).getSeconds());

        state.recordRateLimit(retryAfterSeconds);
        rateLimitState.recordRateLimit(
            provider.getName(),
            resetTime,
            provider.getTypicalRateLimitWindow()
        );

        log.warn("[{}] Rate limited (resetTime={}, retryAfterSeconds={})",
                 providerName, resetTime, retryAfterSeconds);
    }

    private long extractRetryAfter(String errorMessage) {
        if (errorMessage == null) {
            return 0;
        }

        if (errorMessage.contains("Please wait")) {
            String[] parts = errorMessage.split("Please wait ");
            if (parts.length <= 1) {
                throw new IllegalArgumentException("Unable to parse retry-after from error message: " + errorMessage);
            }
            String secondsPart = parts[1].split(" seconds")[0].trim();
            return headerParser.parseRetryAfterHeader(secondsPart);
        }

        if (errorMessage.contains("retry-after")) {
            String[] parts = errorMessage.split("retry-after[: ]+");
            if (parts.length <= 1) {
                throw new IllegalArgumentException("Unable to parse retry-after from error message: " + errorMessage);
            }
            String secondsPart = parts[1].replaceAll("[^0-9]", "");
            return headerParser.parseRetryAfterHeader(secondsPart);
        }

        return 0;
    }

    private ParsedRateLimitInfo parseRateLimitFromHeaders(Headers headers) {
        if (headers == null || headers.isEmpty()) {
            return new ParsedRateLimitInfo(null, 0);
        }

        long retryAfterSeconds = headerParser.parseRetryAfterSeconds(headers);
        if (retryAfterSeconds > 0) {
            return new ParsedRateLimitInfo(null, retryAfterSeconds);
        }

        Instant resetInstant = headerParser.parseResetInstant(headers);
        return new ParsedRateLimitInfo(resetInstant, 0);
    }

    /**
     * Selects the highest-priority configured provider that is currently available.
     */
    public Optional<ApiProvider> selectBestProvider() {
        // Priority order: OpenAI > GitHub Models > Local
        for (ApiProvider provider : new ApiProvider[] {
            ApiProvider.OPENAI,
            ApiProvider.GITHUB_MODELS,
            ApiProvider.LOCAL,
        }) {
            String providerName = sanitizeLogValue(provider.getName());
            if (!isProviderConfigured(provider)) {
                log.debug("[{}] Skipping: not configured", providerName);
                continue;
            }
            if (isProviderAvailable(provider)) {
                log.debug("[{}] Selected as best provider", providerName);
                return Optional.of(provider);
            }
        }

        // Log detailed status for debugging
        for (ApiProvider provider : ApiProvider.values()) {
            String providerName = sanitizeLogValue(provider.getName());
            if (!isProviderConfigured(provider)) {
                log.warn("[{}] Unavailable - missing configuration (API key/token)", providerName);
                continue;
            }
            Duration remaining = rateLimitState.getRemainingWaitTime(
                provider.getName()
            );
            if (!remaining.isZero()) {
                log.warn("[{}] Unavailable - rate limited for {} more", providerName, formatDuration(remaining));
            }
        }

        return Optional.empty();
    }

    private String formatDuration(Duration duration) {
        return RateLimitHeaderParser.formatDuration(duration);
    }

    private static String sanitizeLogValue(String rawValue) {
        if (rawValue == null) {
            return "null";
        }
        return rawValue.replace("\r", "\\r").replace("\n", "\\n");
    }

    /**
     * Clears all in-memory circuit and usage counters without modifying persisted state.
     */
    public void reset() {
        endpointStates.clear();
        log.info("Rate limit manager reset (in-memory state only)");
    }
}
