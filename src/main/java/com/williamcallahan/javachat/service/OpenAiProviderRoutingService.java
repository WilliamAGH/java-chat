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
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.Exceptions;

/**
 * Selects the configured provider and classifies its failures for OpenAI-compatible calls.
 *
 * <p>This service owns provider availability checks, transient failure classification,
 * and configured-provider backoff timing so routing behavior is consistent across
 * streaming and completion code paths. It validates the configured provider during
 * application startup so an explicit invalid selection cannot change routing behavior.</p>
 */
@Service
@Lazy(false)
public class OpenAiProviderRoutingService {
    private static final Logger log = LoggerFactory.getLogger(OpenAiProviderRoutingService.class);

    private static final String PROVIDER_SETTING_OPENAI = "openai";
    private static final String PROVIDER_SETTING_GITHUB_MODELS = "github_models";

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
    private final long configuredProviderBackoffSeconds;
    private final RateLimitService.ApiProvider configuredProvider;

    /** Epoch millis until which the configured provider is temporarily disabled after failure. */
    private volatile long configuredProviderBackoffUntilEpochMs;

    /**
     * Creates provider routing state using the configured provider and backoff values.
     *
     * @param rateLimitService provider rate-limit state tracker
     * @param configuredProviderBackoffSeconds backoff duration after configured-provider transient failure
     * @param configuredProviderSetting configured provider name
     * @throws IllegalArgumentException when the configured provider is blank or unsupported
     */
    public OpenAiProviderRoutingService(
            RateLimitService rateLimitService,
            @Value("${LLM_PRIMARY_BACKOFF_SECONDS:600}") long configuredProviderBackoffSeconds,
            @Value("${LLM_PRIMARY_PROVIDER:github_models}") String configuredProviderSetting) {
        this.rateLimitService = rateLimitService;
        this.configuredProviderBackoffSeconds = configuredProviderBackoffSeconds;
        this.configuredProvider = resolveConfiguredProvider(configuredProviderSetting);
        this.configuredProviderBackoffUntilEpochMs = 0L;
    }

    /**
     * Returns the sole provider selected for chat requests and startup diagnostics.
     *
     * @return configured chat provider
     */
    public RateLimitService.ApiProvider configuredProvider() {
        return configuredProvider;
    }

    /**
     * Resolves the configured provider when its client, rate limit, and backoff permit dispatch.
     *
     * @param githubModelsClient GitHub Models client when configured
     * @param openAiClient OpenAI client when configured
     * @return the configured provider candidate when it is currently callable
     */
    public Optional<OpenAiProviderCandidate> selectConfiguredProviderCandidate(
            OpenAIClient githubModelsClient, OpenAIClient openAiClient) {
        OpenAIClient configuredClient = providerClient(configuredProvider, githubModelsClient, openAiClient);
        if (configuredClient == null) {
            log.warn("Configured provider client is unavailable (providerId={})", configuredProvider.ordinal());
            return Optional.empty();
        }
        OpenAiProviderCandidate providerCandidate = new OpenAiProviderCandidate(configuredClient, configuredProvider);
        return isProviderCandidateAvailable(providerCandidate) ? Optional.of(providerCandidate) : Optional.empty();
    }

    /**
     * Records provider failures and applies configured-provider backoff when eligible.
     *
     * @param provider provider that failed
     * @param throwable failure raised by SDK or transport
     */
    public void recordProviderFailure(RateLimitService.ApiProvider provider, Throwable throwable) {
        if (throwable instanceof OpenAIServiceException serviceException
                && serviceException.statusCode() == HTTP_TOO_MANY_REQUESTS) {
            rateLimitService.recordRateLimitFromOpenAiServiceException(provider, serviceException);
        }

        if (provider == configuredProvider && shouldBackoffConfiguredProvider(throwable)) {
            markConfiguredProviderBackoff();
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

    boolean shouldBackoffConfiguredProvider(Throwable throwable) {
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
            RateLimitService.ApiProvider provider, OpenAIClient githubModelsClient, OpenAIClient openAiClient) {
        return switch (provider) {
            case GITHUB_MODELS -> githubModelsClient;
            case OPENAI -> openAiClient;
            case LOCAL -> null;
        };
    }

    private boolean isProviderCandidateAvailable(OpenAiProviderCandidate providerCandidate) {
        RateLimitService.ApiProvider provider = providerCandidate.provider();
        if (isConfiguredProviderInBackoff()) {
            log.warn("Configured provider unavailable (backoff active, providerId={})", provider.ordinal());
            return false;
        }
        if (rateLimitService.isProviderAvailable(provider)) {
            return true;
        }
        log.warn("Provider unavailable (rate limited, providerId={})", provider.ordinal());
        return false;
    }

    private static RateLimitService.ApiProvider resolveConfiguredProvider(String configuredProviderSetting) {
        String normalizedSetting = configuredProviderSetting == null
                ? ""
                : AsciiTextNormalizer.toLowerAscii(configuredProviderSetting.trim());
        return switch (normalizedSetting) {
            case PROVIDER_SETTING_OPENAI -> RateLimitService.ApiProvider.OPENAI;
            case PROVIDER_SETTING_GITHUB_MODELS -> RateLimitService.ApiProvider.GITHUB_MODELS;
            default -> throw invalidConfiguredProviderSetting(configuredProviderSetting);
        };
    }

    private static IllegalArgumentException invalidConfiguredProviderSetting(String configuredProviderSetting) {
        String settingDescription = configuredProviderSetting == null || configuredProviderSetting.isBlank()
                ? "a blank value"
                : "'" + configuredProviderSetting + "'";
        return new IllegalArgumentException(
                "LLM_PRIMARY_PROVIDER must be 'github_models' or 'openai'; received " + settingDescription + ".");
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

    private boolean isConfiguredProviderInBackoff() {
        return System.currentTimeMillis() < configuredProviderBackoffUntilEpochMs;
    }

    private void markConfiguredProviderBackoff() {
        long backoffEndsAt =
                System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(Math.max(1, configuredProviderBackoffSeconds));
        this.configuredProviderBackoffUntilEpochMs = backoffEndsAt;
        long backoffSecondsRemaining =
                Math.max(1, TimeUnit.MILLISECONDS.toSeconds(backoffEndsAt - System.currentTimeMillis()));
        log.warn("Configured provider temporarily disabled for {}s due to failure", backoffSecondsRemaining);
    }
}
