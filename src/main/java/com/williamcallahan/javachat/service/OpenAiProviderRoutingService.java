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
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.Exceptions;

/**
 * Selects the configured provider and classifies its failures for OpenAI-compatible calls.
 *
 * <p>This service owns provider availability checks, transient failure classification,
 * and configured-provider backoff timing so routing behavior is consistent across
 * streaming and completion code paths.</p>
 */
@Service
public class OpenAiProviderRoutingService {
    private static final Logger log = LoggerFactory.getLogger(OpenAiProviderRoutingService.class);

    private static final String PROVIDER_SETTING_OPENAI = "openai";
    private static final String PROVIDER_SETTING_GITHUB_MODELS = "github_models";
    private static final String PROVIDER_SETTING_GITHUB_MODELS_ALT = "github-models";
    private static final String PROVIDER_SETTING_GITHUB = "github";

    /**
     * Identifies the whole-call timeout message emitted by OkHttp 4.12 {@code RealCall.timeoutExit}.
     *
     * <p>OpenAI Java 4.16 wraps the corresponding {@link InterruptedIOException} in an
     * {@link OpenAIIoException}, which is a provider failure rather than caller cancellation.</p>
     */
    private static final String OK_HTTP_CALL_TIMEOUT_MESSAGE = "timeout";

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
     * Resolves the configured provider when its client, rate limit, and backoff permit dispatch.
     *
     * @param primaryClient GitHub Models client when configured
     * @param secondaryClient OpenAI client when configured
     * @return the configured provider candidate when it is currently callable
     */
    public Optional<OpenAiProviderCandidate> selectConfiguredProviderCandidate(
            OpenAIClient primaryClient, OpenAIClient secondaryClient) {
        RateLimitService.ApiProvider configuredProvider = configuredPrimaryProvider();
        OpenAIClient configuredClient = providerClient(configuredProvider, primaryClient, secondaryClient);
        if (configuredClient == null) {
            log.warn("Configured provider client is unavailable (providerId={})", configuredProvider.ordinal());
            return Optional.empty();
        }
        OpenAiProviderCandidate providerCandidate = new OpenAiProviderCandidate(configuredClient, configuredProvider);
        return isProviderCandidateAvailable(providerCandidate) ? Optional.of(providerCandidate) : Optional.empty();
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
     * Determines whether a streaming failure can be retried without surfacing immediate user error.
     *
     * @param throwable streaming failure
     * @return true when the failure appears transient and retryable
     */
    public boolean isRecoverableStreamingFailure(Throwable throwable) {
        if (throwable == null || isCallerCancellation(throwable)) {
            return false;
        }
        if (throwable instanceof UnauthorizedException
                || throwable instanceof PermissionDeniedException
                || throwable instanceof RateLimitException
                || throwable instanceof NotFoundException) {
            return false;
        }
        if (throwable instanceof OpenAIIoException
                || throwable instanceof SseException
                || Exceptions.isOverflow(throwable)) {
            return true;
        }
        if (throwable instanceof OpenAIServiceException serviceException) {
            int statusCode = serviceException.statusCode();
            return statusCode == HTTP_REQUEST_TIMEOUT
                    || statusCode == HTTP_CONFLICT
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

    boolean shouldBackoffPrimary(Throwable throwable) {
        if (isCallerCancellation(throwable)) {
            return false;
        }
        return isRateLimit(throwable)
                || throwable instanceof OpenAIIoException
                || throwable instanceof UnauthorizedException
                || throwable instanceof PermissionDeniedException
                || throwable instanceof InternalServerException
                || throwable instanceof NotFoundException
                || isServerError(throwable);
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
        if (isPrimaryInBackoff()) {
            log.warn("Configured provider unavailable (backoff active, providerId={})", provider.ordinal());
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

    private boolean isServerError(Throwable throwable) {
        return throwable instanceof OpenAIServiceException serviceException
                && serviceException.statusCode() >= HTTP_INTERNAL_SERVER_ERROR;
    }

    private boolean isCallerCancellation(Throwable throwable) {
        Throwable cancellationCandidate = throwable;
        while (cancellationCandidate != null) {
            if (cancellationCandidate instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return true;
            }
            if (cancellationCandidate instanceof InterruptedIOException interruptedIoException
                    && !(interruptedIoException instanceof SocketTimeoutException)
                    && !isOkHttpCallTimeout(interruptedIoException)) {
                return true;
            }
            String cancellationMessage = cancellationCandidate.getMessage();
            if (cancellationMessage != null
                    && AsciiTextNormalizer.toLowerAscii(cancellationMessage).contains("sleep interrupted")) {
                return true;
            }
            cancellationCandidate = cancellationCandidate.getCause();
        }
        return false;
    }

    private static boolean isOkHttpCallTimeout(InterruptedIOException interruptedIoException) {
        return interruptedIoException.getClass().equals(InterruptedIOException.class)
                && OK_HTTP_CALL_TIMEOUT_MESSAGE.equals(interruptedIoException.getMessage());
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
