package com.williamcallahan.javachat.service;

import com.openai.client.OpenAIClient;
import com.openai.errors.InternalServerException;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.SseException;
import com.openai.errors.UnauthorizedException;
import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.Exceptions;

/**
 * Chooses provider order and fallback eligibility for OpenAI-compatible calls.
 *
 * <p>This service owns provider availability checks, transient failure classification,
 * and primary-provider backoff timing so routing behavior is consistent across
 * streaming and completion code paths.</p>
 */
@Service
public class OpenAiProviderRoutingService {
    private static final Logger log = LoggerFactory.getLogger(OpenAiProviderRoutingService.class);

    private static final String PROVIDER_SETTING_OPENAI = "openai";
    private static final String PROVIDER_SETTING_GITHUB_MODELS = "github_models";
    private static final String PROVIDER_SETTING_GITHUB_MODELS_ALT = "github-models";
    private static final String PROVIDER_SETTING_GITHUB = "github";

    private static final int HTTP_REQUEST_TIMEOUT = 408;
    private static final int HTTP_CONFLICT = 409;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_INTERNAL_SERVER_ERROR = 500;

    private final RateLimitService rateLimitService;
    private final long primaryBackoffSeconds;
    private final String primaryProviderSetting;

    /** Epoch millis until which the primary provider is temporarily disabled after failure. */
    private volatile long primaryBackoffUntilEpochMs;

    /**
     * Creates provider routing state using configured provider priority and backoff values.
     *
     * @param rateLimitService provider rate-limit state tracker
     * @param primaryBackoffSeconds backoff duration after primary provider transient failure
     * @param primaryProviderSetting configured provider priority name
     */
    public OpenAiProviderRoutingService(
            RateLimitService rateLimitService,
            @Value("${LLM_PRIMARY_BACKOFF_SECONDS:600}") long primaryBackoffSeconds,
            @Value("${LLM_PRIMARY_PROVIDER:github_models}") String primaryProviderSetting) {
        this.rateLimitService = rateLimitService;
        this.primaryBackoffSeconds = primaryBackoffSeconds;
        this.primaryProviderSetting = primaryProviderSetting;
        this.primaryBackoffUntilEpochMs = 0L;
    }

    /**
     * Resolves providers in deterministic order and filters unavailable candidates.
     *
     * @param primaryClient GitHub Models client when configured
     * @param secondaryClient OpenAI client when configured
     * @return ordered provider candidates that are currently callable
     */
    public List<OpenAiProviderCandidate> selectAvailableProviderCandidates(
            OpenAIClient primaryClient, OpenAIClient secondaryClient) {
        List<OpenAiProviderCandidate> orderedCandidates = orderedProviderCandidates(primaryClient, secondaryClient);
        List<OpenAiProviderCandidate> availableCandidates = new ArrayList<>(orderedCandidates.size());
        for (OpenAiProviderCandidate providerCandidate : orderedCandidates) {
            if (isProviderCandidateAvailable(providerCandidate)) {
                availableCandidates.add(providerCandidate);
            }
        }
        return List.copyOf(availableCandidates);
    }

    /**
     * Records provider failures and applies primary-provider backoff when eligible.
     *
     * @param provider provider that failed
     * @param throwable failure raised by SDK or transport
     */
    public void recordProviderFailure(RateLimitService.ApiProvider provider, Throwable throwable) {
        if (throwable instanceof OpenAIServiceException serviceException
                && serviceException.statusCode() == HTTP_TOO_MANY_REQUESTS) {
            rateLimitService.recordRateLimitFromOpenAiServiceException(provider, serviceException);
        }

        if (provider == configuredPrimaryProvider() && shouldBackoffPrimary(throwable)) {
            markPrimaryBackoff();
        }
    }

    /**
     * Determines whether a completion failure should trigger provider fallback.
     *
     * @param throwable completion failure
     * @return true when retrying another provider is likely to succeed
     */
    public boolean isCompletionFallbackEligible(Throwable throwable) {
        if (shouldBackoffPrimary(throwable)) {
            return true;
        }
        if (throwable instanceof NotFoundException) {
            return true;
        }
        if (throwable instanceof OpenAIServiceException serviceException) {
            return serviceException.statusCode() == HTTP_REQUEST_TIMEOUT;
        }
        String exceptionMessage = throwable.getMessage();
        if (exceptionMessage == null) {
            return false;
        }
        String normalizedMessage = AsciiTextNormalizer.toLowerAscii(exceptionMessage);
        return normalizedMessage.contains("timeout")
                || normalizedMessage.contains("temporarily unavailable")
                || normalizedMessage.contains("connection reset")
                || normalizedMessage.contains("connection closed");
    }

    /**
     * Determines whether a streaming failure should trigger provider fallback.
     *
     * @param throwable streaming failure
     * @return true when retrying another provider before first token is likely to succeed
     */
    public boolean isStreamingFallbackEligible(Throwable throwable) {
        if (shouldBackoffPrimary(throwable)) {
            return true;
        }
        if (throwable instanceof SseException || Exceptions.isOverflow(throwable)) {
            return true;
        }
        if (throwable instanceof OpenAIServiceException serviceException) {
            int statusCode = serviceException.statusCode();
            return statusCode == HTTP_REQUEST_TIMEOUT
                    || statusCode == HTTP_CONFLICT
                    || statusCode == HTTP_TOO_MANY_REQUESTS
                    || statusCode >= HTTP_INTERNAL_SERVER_ERROR;
        }
        String exceptionMessage = throwable.getMessage();
        if (exceptionMessage == null) {
            return false;
        }
        String normalizedMessage = AsciiTextNormalizer.toLowerAscii(exceptionMessage);
        return normalizedMessage.contains("invalid stream")
                || normalizedMessage.contains("malformed")
                || normalizedMessage.contains("unexpected end of json input")
                || normalizedMessage.contains("timeout")
                || normalizedMessage.contains("temporarily unavailable")
                || normalizedMessage.contains("connection reset")
                || normalizedMessage.contains("connection closed");
    }

    /**
     * Determines whether a streaming failure can be retried without surfacing immediate user error.
     *
     * @param throwable streaming failure
     * @return true when the failure appears transient and retryable
     */
    public boolean isRecoverableStreamingFailure(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        return isStreamingFallbackEligible(throwable);
    }

    boolean shouldBackoffPrimary(Throwable throwable) {
        if (isRateLimit(throwable)) {
            return true;
        }
        if (throwable instanceof OpenAIIoException) {
            return true;
        }
        if (throwable instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return true;
        }
        if (throwable instanceof UnauthorizedException || throwable instanceof PermissionDeniedException) {
            return true;
        }
        if (throwable instanceof InternalServerException) {
            return true;
        }
        if (throwable instanceof OpenAIServiceException serviceException) {
            return serviceException.statusCode() >= HTTP_INTERNAL_SERVER_ERROR;
        }
        String message = throwable.getMessage();
        return message != null && AsciiTextNormalizer.toLowerAscii(message).contains("sleep interrupted");
    }

    private List<OpenAiProviderCandidate> orderedProviderCandidates(
            OpenAIClient primaryClient, OpenAIClient secondaryClient) {
        RateLimitService.ApiProvider configuredPrimary = configuredPrimaryProvider();
        RateLimitService.ApiProvider configuredSecondary =
                configuredPrimary == RateLimitService.ApiProvider.GITHUB_MODELS
                        ? RateLimitService.ApiProvider.OPENAI
                        : RateLimitService.ApiProvider.GITHUB_MODELS;

        List<OpenAiProviderCandidate> candidates = new ArrayList<>(2);
        addProviderCandidate(candidates, configuredPrimary, primaryClient, secondaryClient);
        addProviderCandidate(candidates, configuredSecondary, primaryClient, secondaryClient);
        return List.copyOf(candidates);
    }

    private void addProviderCandidate(
            List<OpenAiProviderCandidate> candidates,
            RateLimitService.ApiProvider provider,
            OpenAIClient primaryClient,
            OpenAIClient secondaryClient) {
        OpenAIClient providerClient = providerClient(provider, primaryClient, secondaryClient);
        if (providerClient == null) {
            return;
        }
        candidates.add(new OpenAiProviderCandidate(providerClient, provider));
    }

    private OpenAIClient providerClient(
            RateLimitService.ApiProvider provider, OpenAIClient primaryClient, OpenAIClient secondaryClient) {
        return switch (provider) {
            case GITHUB_MODELS -> primaryClient;
            case OPENAI -> secondaryClient;
            case LOCAL -> null;
        };
    }

    private boolean isProviderCandidateAvailable(OpenAiProviderCandidate providerCandidate) {
        RateLimitService.ApiProvider provider = providerCandidate.provider();
        if (provider == configuredPrimaryProvider() && isPrimaryInBackoff()) {
            log.warn("Primary provider unavailable (backoff active, providerId={})", provider.ordinal());
            return false;
        }
        if (rateLimitService.isProviderAvailable(provider)) {
            return true;
        }
        log.warn("Provider unavailable (rate limited, providerId={})", provider.ordinal());
        return false;
    }

    private RateLimitService.ApiProvider configuredPrimaryProvider() {
        String normalizedSetting = primaryProviderSetting == null
                ? PROVIDER_SETTING_GITHUB_MODELS
                : AsciiTextNormalizer.toLowerAscii(primaryProviderSetting.trim());
        return switch (normalizedSetting) {
            case PROVIDER_SETTING_OPENAI -> RateLimitService.ApiProvider.OPENAI;
            case PROVIDER_SETTING_GITHUB_MODELS, PROVIDER_SETTING_GITHUB_MODELS_ALT, PROVIDER_SETTING_GITHUB ->
                RateLimitService.ApiProvider.GITHUB_MODELS;
            default -> {
                log.warn(
                        "Unknown LLM_PRIMARY_PROVIDER value '{}', defaulting to '{}'",
                        primaryProviderSetting,
                        PROVIDER_SETTING_GITHUB_MODELS);
                yield RateLimitService.ApiProvider.GITHUB_MODELS;
            }
        };
    }

    private boolean isRateLimit(Throwable throwable) {
        return throwable instanceof RateLimitException
                || (throwable instanceof OpenAIServiceException serviceException
                        && serviceException.statusCode() == HTTP_TOO_MANY_REQUESTS);
    }

    private boolean isPrimaryInBackoff() {
        return System.currentTimeMillis() < primaryBackoffUntilEpochMs;
    }

    private void markPrimaryBackoff() {
        long backoffEndsAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(Math.max(1, primaryBackoffSeconds));
        this.primaryBackoffUntilEpochMs = backoffEndsAt;
        long backoffSecondsRemaining =
                Math.max(1, TimeUnit.MILLISECONDS.toSeconds(backoffEndsAt - System.currentTimeMillis()));
        log.warn("Primary provider temporarily disabled for {}s due to failure", backoffSecondsRemaining);
    }
}
