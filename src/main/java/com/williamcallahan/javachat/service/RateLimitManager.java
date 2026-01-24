package com.williamcallahan.javachat.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
    private final Map<String, AtomicInteger> dailyUsage =
        new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> resetTimes =
        new ConcurrentHashMap<>();
    private final Environment env;

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

        // Testing live refresh functionality
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

    private boolean hasText(String valueText) {
        return valueText != null && !valueText.trim().isEmpty();
    }

    /**
     * Reports whether the provider can be used right now based on configuration, persisted rate limits, and in-memory backoff.
     */
    public boolean isProviderAvailable(ApiProvider provider) {
        // First check if provider is actually configured
        if (!isProviderConfigured(provider)) {
            log.debug("[{}] Not configured; treating as unavailable", provider.getName());
            return false;
        }

        // Then check persistent rate limit state
        if (!rateLimitState.isAvailable(provider.getName())) {
            Duration remaining = rateLimitState.getRemainingWaitTime(
                provider.getName()
            );
            if (!remaining.isZero()) {
                log.debug("[{}] Rate limited (persistent state)", provider.getName());
                return false;
            }
        }

        // Then check in-memory circuit breaker state
        ApiEndpointState state = endpointStates.computeIfAbsent(
            provider.getName(),
            providerKey -> new ApiEndpointState()
        );

        if (!state.isAvailable()) {
            log.debug("[{}] In circuit breaker state", provider.getName());
            return false;
        }

        if (state.getRequestsToday() >= provider.dailyLimit) {
            log.warn("[{}] Reached daily limit", provider.getName());
            return false;
        }

        return true;
    }

    /**
     * Records a successful provider request in both in-memory and persisted rate limit state.
     */
    public void recordSuccess(ApiProvider provider) {
        // Update both in-memory and persistent state
        ApiEndpointState state = endpointStates.computeIfAbsent(
            provider.getName(),
            providerKey -> new ApiEndpointState()
        );
        state.recordSuccess();
        rateLimitState.recordSuccess(provider.getName());

        log.debug("[{}] Request successful", provider.getName());
    }

    /**
     * Records a rate limit for the provider using best-effort parsing of reset or retry information.
     */
    public void recordRateLimit(ApiProvider provider, String errorMessage) {
        // Extract reset time from error or headers
        Instant resetTime = parseResetTimeFromError(errorMessage);
        long retryAfterSeconds = extractRetryAfter(errorMessage);

        // For GitHub Models, use longer backoff as they have strict limits
        if (provider == ApiProvider.GITHUB_MODELS) {
            if (resetTime == null && retryAfterSeconds == 0) {
                // GitHub typically has 24-hour rate limits
                resetTime = Instant.now().plus(Duration.ofHours(24));
                log.info("[{}] Rate limited - applying 24-hour backoff", provider.getName());
            }
        }

        // Update in-memory state
        ApiEndpointState state = endpointStates.computeIfAbsent(
            provider.getName(),
            providerKey -> new ApiEndpointState()
        );
        state.recordRateLimit(retryAfterSeconds);

        // Update persistent state with proper window
        rateLimitState.recordRateLimit(
            provider.getName(),
            resetTime,
            provider.getTypicalRateLimitWindow()
        );

        log.warn("[{}] Rate limited (errorMessage={})", provider.getName(), errorMessage);
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
            recordRateLimitInternal(provider, Instant.now().plusSeconds(rateLimitInfo.retryAfterSeconds), rateLimitInfo.retryAfterSeconds);
            return;
        }

        if (rateLimitInfo.resetTime != null) {
            long retryAfterSeconds = Math.max(0, Duration.between(Instant.now(), rateLimitInfo.resetTime).getSeconds());
            recordRateLimitInternal(provider, rateLimitInfo.resetTime, retryAfterSeconds);
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
        } catch (NumberFormatException exception) {
            try {
                return Instant.parse(resetHeader);
            } catch (DateTimeParseException parseException) {
                log.debug("Could not parse rate limit reset header");
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
        } catch (NumberFormatException exception) {
            log.debug("Could not parse Retry-After header");
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
            providerKey -> new ApiEndpointState()
        );
        long secondsUntilReset = Math.max(
            0,
            Duration.between(Instant.now(), resetTime).getSeconds()
        );
        state.recordRateLimit(secondsUntilReset);
    }

    private Instant parseResetTimeFromError(String errorMessage) {
        // Try to parse reset time from error message
        // Implementation would parse various formats
        // For now, return null and rely on window-based calculation
        return null;
    }

    private long extractRetryAfter(String errorMessage) {
        if (errorMessage == null) return 0;

        if (errorMessage.contains("Please wait")) {
            String[] parts = errorMessage.split("Please wait ");
            if (parts.length > 1) {
                String secondsPart = parts[1].split(" seconds")[0].trim();
                if (isDigits(secondsPart)) {
                    return Long.parseLong(secondsPart);
                }
            }
        }

        if (errorMessage.contains("retry-after")) {
            String[] parts = errorMessage.split("retry-after[: ]+");
            if (parts.length > 1) {
                String secondsPart = parts[1].replaceAll("[^0-9]", "");
                if (isDigits(secondsPart)) {
                    return Long.parseLong(secondsPart);
                }
            }
        }

        return 0;
    }

    private ParsedRateLimitInfo parseRateLimitFromHeaders(Headers headers) {
        if (headers == null || headers.isEmpty()) {
            return new ParsedRateLimitInfo(null, 0);
        }

        long retryAfterSeconds = parseRetryAfterSeconds(headers);
        if (retryAfterSeconds > 0) {
            return new ParsedRateLimitInfo(null, retryAfterSeconds);
        }

        Instant resetInstant = parseResetInstant(headers);
        return new ParsedRateLimitInfo(resetInstant, 0);
    }

    private long parseRetryAfterSeconds(Headers headers) {
        String retryAfter = firstHeaderValue(headers, "Retry-After");
        if (retryAfter == null) {
            return 0;
        }

        String trimmed = retryAfter.trim();
        if (isDigits(trimmed)) {
            return Long.parseLong(trimmed);
        }

        try {
            ZonedDateTime httpDate = ZonedDateTime.parse(trimmed, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
            long seconds = Duration.between(Instant.now(), httpDate.toInstant()).getSeconds();
            return Math.max(0, seconds);
        } catch (RuntimeException parseFailed) {
            return 0;
        }
    }

    private Instant parseResetInstant(Headers headers) {
        String resetSeconds = firstHeaderValue(headers, "X-RateLimit-Reset");
        if (resetSeconds != null) {
            Instant parsed = parseInstantSecondsFromEpoch(resetSeconds);
            if (parsed != null) {
                return parsed;
            }
        }

        // OpenAI commonly returns reset windows like "1s", "2m", etc. Prefer the shortest if multiple are present.
        long candidateSeconds = minPositive(
            parseDurationSeconds(firstHeaderValue(headers, "x-ratelimit-reset-requests")),
            parseDurationSeconds(firstHeaderValue(headers, "x-ratelimit-reset-tokens")),
            parseDurationSeconds(firstHeaderValue(headers, "x-ratelimit-reset")),
            parseDurationSeconds(firstHeaderValue(headers, "X-RateLimit-Reset-Requests")),
            parseDurationSeconds(firstHeaderValue(headers, "X-RateLimit-Reset-Tokens"))
        );
        if (candidateSeconds > 0) {
            return Instant.now().plusSeconds(candidateSeconds);
        }

        return null;
    }

    private Instant parseInstantSecondsFromEpoch(String rawSeconds) {
        if (rawSeconds == null) {
            return null;
        }
        String trimmed = rawSeconds.trim();
        if (!isDigits(trimmed)) {
            return null;
        }
        try {
            long epochSeconds = Long.parseLong(trimmed);
            return Instant.ofEpochSecond(epochSeconds);
        } catch (RuntimeException parseFailed) {
            return null;
        }
    }

    private long parseDurationSeconds(String rawDuration) {
        if (rawDuration == null) {
            return 0;
        }
        String trimmed = rawDuration.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }

        int lastIndex = trimmed.length() - 1;
        char lastChar = trimmed.charAt(lastIndex);
        if (isDigits(trimmed)) {
            return Long.parseLong(trimmed);
        }

        if (lastChar == 's') {
            String numberPart = trimmed.substring(0, lastIndex).trim();
            if (isDigits(numberPart)) {
                return Long.parseLong(numberPart);
            }
        }
        if (lastChar == 'm') {
            String numberPart = trimmed.substring(0, lastIndex).trim();
            if (isDigits(numberPart)) {
                return TimeUnit.MINUTES.toSeconds(Long.parseLong(numberPart));
            }
        }
        if (lastChar == 'h') {
            String numberPart = trimmed.substring(0, lastIndex).trim();
            if (isDigits(numberPart)) {
                return TimeUnit.HOURS.toSeconds(Long.parseLong(numberPart));
            }
        }
        if (trimmed.endsWith("ms")) {
            String numberPart = trimmed.substring(0, trimmed.length() - 2).trim();
            if (isDigits(numberPart)) {
                long millis = Long.parseLong(numberPart);
                return Math.max(1, TimeUnit.MILLISECONDS.toSeconds(millis));
            }
        }

        return 0;
    }

    private long minPositive(long... candidates) {
        long min = 0;
        for (long candidate : candidates) {
            if (candidate <= 0) {
                continue;
            }
            if (min == 0 || candidate < min) {
                min = candidate;
            }
        }
        return min;
    }

    private String firstHeaderValue(Headers headers, String name) {
        if (headers == null || name == null || name.isBlank()) {
            return null;
        }
        if (!headers.names().contains(name)) {
            // `Headers` is case-insensitive, but the Set may not contain exactly matching names.
            for (String headerName : headers.names()) {
                if (headerName != null && headerName.equalsIgnoreCase(name)) {
                    return firstHeaderValue(headers, headerName);
                }
            }
            return null;
        }
        var values = headers.values(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    private void recordRateLimitInternal(ApiProvider provider, Instant resetTime, long retryAfterSeconds) {
        ApiEndpointState state = endpointStates.computeIfAbsent(
            provider.getName(),
            providerKey -> new ApiEndpointState()
        );
        state.recordRateLimit(retryAfterSeconds);

        rateLimitState.recordRateLimit(
            provider.getName(),
            resetTime,
            provider.getTypicalRateLimitWindow()
        );

        log.warn("[{}] Rate limited (resetTime={}, retryAfterSeconds={})",
                provider.getName(), resetTime, retryAfterSeconds);
    }

    private boolean isDigits(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        for (int index = 0; index < candidate.length(); index++) {
            if (!Character.isDigit(candidate.charAt(index))) {
                return false;
            }
        }
        return true;
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
            if (!isProviderConfigured(provider)) {
                log.debug("[{}] Skipping: not configured", provider.getName());
                continue;
            }
            if (isProviderAvailable(provider)) {
                log.debug("[{}] Selected as best provider", provider.getName());
                return Optional.of(provider);
            }
        }

        // Log detailed status for debugging
        for (ApiProvider provider : ApiProvider.values()) {
            if (!isProviderConfigured(provider)) {
                log.warn("[{}] Unavailable - missing configuration (API key/token)", provider.getName());
                continue;
            }
            Duration remaining = rateLimitState.getRemainingWaitTime(
                provider.getName()
            );
            if (!remaining.isZero()) {
                log.warn("[{}] Unavailable - rate limited for {} more", provider.getName(), formatDuration(remaining));
            }
        }

        return Optional.empty();
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

    /**
     * Clears all in-memory circuit and usage counters without modifying persisted state.
     */
    public void reset() {
        endpointStates.clear();
        dailyUsage.clear();
        resetTimes.clear();
        log.info("Rate limit manager reset (in-memory state only)");
    }
}
