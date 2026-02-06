package com.williamcallahan.javachat.service;

import com.openai.errors.OpenAIServiceException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Coordinates provider availability decisions from persisted and in-memory rate-limit state.
 *
 * <p>This service only accepts explicit header-derived timing signals for rate limiting.
 * It intentionally rejects message parsing and implicit fallback behavior.</p>
 */
@Component
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    /** OpenAI tier-1 daily request cap used for local protective throttling. */
    private static final int OPENAI_DAILY_LIMIT = 500;

    /** GitHub Models preview daily cap used for local protective throttling. */
    private static final int GITHUB_MODELS_DAILY_LIMIT = 150;

    /** OpenAI rate-limit persistence window key. */
    private static final String OPENAI_RATE_LIMIT_WINDOW = "1m";

    /** GitHub Models rate-limit persistence window key. */
    private static final String GITHUB_MODELS_RATE_LIMIT_WINDOW = "24h";

    /** Maximum exponential backoff multiplier used by in-memory circuit state. */
    private static final int MAX_BACKOFF_MULTIPLIER = 32;

    private final RateLimitState rateLimitState;
    private final Map<String, ProviderCircuitState> endpointStates = new ConcurrentHashMap<>();
    private final Environment env;
    private final RateLimitDecisionResolver decisionResolver;

    /**
     * Describes supported providers and their persistence/rate-limit metadata.
     */
    public enum ApiProvider {
        /** OpenAI provider. */
        OPENAI("openai", OPENAI_DAILY_LIMIT, OPENAI_RATE_LIMIT_WINDOW),
        /** GitHub Models provider. */
        GITHUB_MODELS("github_models", GITHUB_MODELS_DAILY_LIMIT, GITHUB_MODELS_RATE_LIMIT_WINDOW),
        /** Local model provider without network rate limits. */
        LOCAL("local", Integer.MAX_VALUE, null);

        private final String name;
        private final int dailyLimit;
        private final String typicalRateLimitWindow;

        ApiProvider(String name, int dailyLimit, String typicalRateLimitWindow) {
            this.name = name;
            this.dailyLimit = dailyLimit;
            this.typicalRateLimitWindow = typicalRateLimitWindow;
        }

        /**
         * Returns provider identifier used as persistence key.
         */
        public String getName() {
            return name;
        }

        /**
         * Returns provider persistence rate-limit window descriptor.
         */
        public String getTypicalRateLimitWindow() {
            return typicalRateLimitWindow;
        }
    }

    /**
     * Creates a rate-limit service backed by persistent state and strict header-based decisions.
     */
    public RateLimitService(RateLimitState rateLimitState, Environment env) {
        this.rateLimitState = Objects.requireNonNull(rateLimitState, "rateLimitState");
        this.env = Objects.requireNonNull(env, "env");
        this.decisionResolver = new RateLimitDecisionResolver(new RateLimitHeaderParser());
        log.info("RateLimitService initialized with strict header-based decisions");
    }

    /**
     * Returns whether the provider is eligible for requests right now.
     */
    public boolean isProviderAvailable(ApiProvider provider) {
        ApiProvider requiredProvider = Objects.requireNonNull(provider, "provider");
        String providerName = providerName(requiredProvider);

        if (!isProviderConfigured(requiredProvider)) {
            log.debug("[{}] Not configured; treating as unavailable", providerName);
            return false;
        }

        if (!rateLimitState.isAvailable(providerName)) {
            log.debug("[{}] Rate limited (persistent state)", providerName);
            return false;
        }

        ProviderCircuitState state = getOrCreateEndpointState(requiredProvider);
        if (!state.isAvailable()) {
            log.debug("[{}] In circuit breaker state", providerName);
            return false;
        }

        if (state.requestsToday() >= requiredProvider.dailyLimit) {
            log.warn("[{}] Reached daily limit", providerName);
            return false;
        }

        return true;
    }

    /**
     * Records a successful provider request in both in-memory and persistent state.
     */
    public void recordSuccess(ApiProvider provider) {
        ApiProvider requiredProvider = Objects.requireNonNull(provider, "provider");
        String providerName = providerName(requiredProvider);

        ProviderCircuitState state = getOrCreateEndpointState(requiredProvider);
        state.recordSuccess();
        rateLimitState.recordSuccess(providerName);

        log.debug("[{}] Request successful", providerName);
    }

    /**
     * Records a rate limit from OpenAI SDK exceptions using only authoritative headers.
     *
     * @throws RateLimitDecisionException when headers are missing/invalid or status is not 429
     */
    public void recordRateLimitFromOpenAiServiceException(ApiProvider provider, OpenAIServiceException exception) {
        ApiProvider requiredProvider = Objects.requireNonNull(provider, "provider");
        OpenAIServiceException requiredException = Objects.requireNonNull(exception, "exception");

        if (requiredException.statusCode() != 429) {
            throw new RateLimitDecisionException(
                    "OpenAI rate-limit recording requires HTTP 429, got " + requiredException.statusCode());
        }

        RateLimitDecision decision = decisionResolver.resolveFromOpenAiHeaders(requiredException.headers());
        applyRateLimit(requiredProvider, decision);
    }

    /**
     * Records a rate limit from WebClient exceptions using Retry-After/X-RateLimit-Reset headers.
     *
     * @throws RateLimitDecisionException when headers are missing/invalid or error type is unsupported
     */
    public void recordRateLimitFromException(ApiProvider provider, Throwable error) {
        ApiProvider requiredProvider = Objects.requireNonNull(provider, "provider");
        Throwable requiredError = Objects.requireNonNull(error, "error");

        if (!(requiredError instanceof WebClientResponseException webClientError)) {
            throw new RateLimitDecisionException(
                    "Rate-limit recording requires WebClientResponseException with headers", requiredError);
        }

        RateLimitDecision decision = decisionResolver.resolveFromWebClientException(webClientError);
        applyRateLimit(requiredProvider, decision);
    }

    /**
     * Clears all in-memory circuit and request counters without changing persisted state.
     */
    public void reset() {
        endpointStates.clear();
        log.info("Rate limit manager reset (in-memory state only)");
    }

    private boolean isProviderConfigured(ApiProvider provider) {
        return switch (provider) {
            case OPENAI -> hasText(env.getProperty("OPENAI_API_KEY"));
            case GITHUB_MODELS -> hasText(env.getProperty("GITHUB_TOKEN"));
            case LOCAL -> true;
        };
    }

    private ProviderCircuitState getOrCreateEndpointState(ApiProvider provider) {
        String providerName = providerName(provider);
        return endpointStates.computeIfAbsent(
                providerName, ignoredKey -> new ProviderCircuitState(MAX_BACKOFF_MULTIPLIER));
    }

    private void applyRateLimit(ApiProvider provider, RateLimitDecision decision) {
        String providerName = providerName(provider);
        ProviderCircuitState state = getOrCreateEndpointState(provider);

        state.recordRateLimit(decision.retryAfterSeconds());
        rateLimitState.recordRateLimit(providerName, decision.resetTime(), provider.getTypicalRateLimitWindow());

        log.warn("[{}] Rate limited (retryAfterSeconds={})", providerName, decision.retryAfterSeconds());
    }

    private static String providerName(ApiProvider provider) {
        return provider.getName();
    }

    private boolean hasText(String valueText) {
        return valueText != null && !valueText.trim().isEmpty();
    }
}
