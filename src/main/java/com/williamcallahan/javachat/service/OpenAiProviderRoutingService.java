package com.williamcallahan.javachat.service;

import com.openai.client.OpenAIClient;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.RateLimitException;
import com.openai.errors.SseException;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.ConfiguredProviderBackoff;
import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.Exceptions;

/**
 * Owns OpenAI admission and classifies provider failures for OpenAI-compatible calls.
 *
 * <p>This service owns provider availability checks, transient failure classification,
 * and configured-provider backoff timing so behavior is consistent across streaming
 * and completion code paths.</p>
 */
@Service
@Lazy(false)
public final class OpenAiProviderRoutingService {
    private static final Logger log = LoggerFactory.getLogger(OpenAiProviderRoutingService.class);
    private static final RateLimitService.ApiProvider CONFIGURED_PROVIDER = RateLimitService.ApiProvider.OPENAI;
    /**
     * Identifies the whole-call timeout message emitted by OkHttp 4.12 {@code RealCall.timeoutExit}.
     *
     * <p>OpenAI Java 4.43.0 wraps the corresponding {@link InterruptedIOException} in an
     * {@link OpenAIIoException}. The timeout belongs to the caller-owned request budget, so it
     * remains a request-local retryable failure without disabling the provider for other requests.</p>
     */
    private static final String OK_HTTP_CALL_TIMEOUT_MESSAGE = "timeout";

    private static final int HTTP_REQUEST_TIMEOUT = 408;
    private static final int HTTP_CONFLICT = 409;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_INTERNAL_SERVER_ERROR = 500;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_NOT_FOUND = 404;

    private final RateLimitService rateLimitService;
    private final ConfiguredProviderBackoff configuredProviderBackoff;
    private final InstantSource instantSource;

    /** Instant until which the configured provider is temporarily disabled after failure. */
    private volatile Instant configuredProviderBackoffUntil;

    /**
     * Creates provider admission state using the configured provider and backoff policy.
     *
     * @param rateLimitService provider rate-limit state tracker
     * @param appProperties typed source of configured-provider backoff policy
     * @throws IllegalArgumentException when the backoff configuration is invalid
     */
    @Autowired
    public OpenAiProviderRoutingService(RateLimitService rateLimitService, AppProperties appProperties) {
        this(rateLimitService, appProperties, InstantSource.system());
    }

    OpenAiProviderRoutingService(
            RateLimitService rateLimitService, AppProperties appProperties, InstantSource instantSource) {
        this.rateLimitService = Objects.requireNonNull(rateLimitService, "rateLimitService");
        this.configuredProviderBackoff =
                Objects.requireNonNull(appProperties, "appProperties").getLlm().configuredProviderBackoff();
        this.instantSource = Objects.requireNonNull(instantSource, "instantSource");
        this.configuredProviderBackoffUntil = Instant.MIN;
    }

    /**
     * Returns the sole provider selected for chat requests and startup diagnostics.
     *
     * @return configured chat provider
     */
    public RateLimitService.ApiProvider configuredProvider() {
        return CONFIGURED_PROVIDER;
    }

    /**
     * Returns whether the configured provider has a client available for dispatch.
     *
     * @param openAiClient OpenAI client
     * @return true when the configured provider client is present
     */
    public boolean hasConfiguredProviderClient(OpenAIClient openAiClient) {
        return openAiClient != null;
    }

    /**
     * Returns whether the configured provider can accept work before retrieval begins.
     *
     * <p>This non-reserving check prevents expensive prompt preparation during a known cooldown
     * or rate-limit window. Dispatch still performs the atomic reservation because availability
     * can change between preparation and the SDK call.</p>
     *
     * @param openAiClient OpenAI client
     * @return true when the configured provider client is present and currently eligible
     */
    public synchronized boolean canAttemptConfiguredProviderRequest(OpenAIClient openAiClient) {
        return openAiClient != null
                && !isConfiguredProviderInBackoff()
                && rateLimitService.isProviderAvailable(CONFIGURED_PROVIDER);
    }

    /**
     * Atomically admits one request to the configured provider immediately before SDK dispatch.
     *
     * <p>The synchronized cooldown check and rate-limit reservation form the single admission
     * boundary for both streaming and completion requests.</p>
     *
     * @param openAiClient OpenAI client
     * @return the admitted configured-provider candidate, or empty when its client is missing
     * @throws ConfiguredProviderTemporarilyUnavailableException when cooldown or rate limiting denies admission
     */
    public synchronized Optional<OpenAiProviderCandidate> admitConfiguredProviderRequest(OpenAIClient openAiClient) {
        if (openAiClient == null) {
            log.warn("Configured provider client is unavailable (providerId={})", CONFIGURED_PROVIDER.ordinal());
            return Optional.empty();
        }
        OpenAiProviderCandidate providerCandidate = new OpenAiProviderCandidate(openAiClient, CONFIGURED_PROVIDER);
        requireConfiguredProviderAdmission();
        return Optional.of(providerCandidate);
    }

    /**
     * Records provider failures and applies configured-provider backoff when eligible.
     *
     * @param provider provider that failed
     * @param throwable failure raised by SDK or transport
     * @throws RateLimitDecisionException when rate-limit timing headers are missing or invalid
     */
    public synchronized void recordProviderFailure(RateLimitService.ApiProvider provider, Throwable throwable) {
        if (provider == CONFIGURED_PROVIDER && shouldBackoffConfiguredProvider(throwable)) {
            markConfiguredProviderBackoff();
        }

        if (throwable instanceof OpenAIServiceException serviceException
                && serviceException.statusCode() == HTTP_TOO_MANY_REQUESTS) {
            try {
                rateLimitService.recordRateLimitFromOpenAiServiceException(provider, serviceException);
            } catch (RateLimitDecisionException rateLimitDecisionFailure) {
                if (provider == CONFIGURED_PROVIDER) {
                    markConfiguredProviderBackoff();
                }
                throw rateLimitDecisionFailure;
            }
        }
    }

    /**
     * Records a successful configured-provider request without erasing a newer cooldown.
     *
     * <p>A success observed during an active cooldown belongs to a request admitted before the
     * failure that opened it. Only an expired local deadline is stale.</p>
     *
     * @param provider provider that completed successfully
     */
    public synchronized void recordProviderSuccess(RateLimitService.ApiProvider provider) {
        if (provider == CONFIGURED_PROVIDER && !instantSource.instant().isBefore(configuredProviderBackoffUntil)) {
            configuredProviderBackoffUntil = Instant.MIN;
        }
        rateLimitService.recordSuccess(provider);
    }

    /**
     * Determines whether a streaming failure can be retried without surfacing immediate user error.
     *
     * @param throwable streaming failure
     * @return true when the failure appears transient and retryable
     */
    public boolean isRecoverableStreamingFailure(Throwable throwable) {
        if (throwable == null || isCallerCancellation(throwable) || containsPermanentProviderFailure(throwable)) {
            return false;
        }
        Set<Throwable> visitedFailures = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable failureCandidate = throwable;
        while (failureCandidate != null && visitedFailures.add(failureCandidate)) {
            if (failureCandidate instanceof ConfiguredProviderTemporarilyUnavailableException
                    || failureCandidate instanceof EmbeddingServiceTemporarilyUnavailableException
                    || failureCandidate instanceof OpenAIIoException
                    || failureCandidate instanceof OpenAIRetryableException
                    || failureCandidate instanceof SseException
                    || failureCandidate instanceof TimeoutException
                    || Exceptions.isOverflow(failureCandidate)) {
                return true;
            }
            if (failureCandidate instanceof HybridSearchPartialFailureException retrievalFailure) {
                return retrievalFailure.isRetryable();
            }
            if (failureCandidate instanceof RateLimitException) {
                return false;
            }
            if (failureCandidate instanceof OpenAiResponseException responseFailure) {
                return responseFailure.isRetryable();
            }
            if (failureCandidate instanceof OpenAIServiceException serviceException) {
                int statusCode = serviceException.statusCode();
                return statusCode == HTTP_REQUEST_TIMEOUT
                        || statusCode == HTTP_CONFLICT
                        || statusCode >= HTTP_INTERNAL_SERVER_ERROR;
            }
            failureCandidate = failureCandidate.getCause();
        }
        return false;
    }

    boolean shouldBackoffConfiguredProvider(Throwable throwable) {
        if (isCallerCancellation(throwable)
                || containsOkHttpCallTimeout(throwable)
                || containsPermanentProviderFailure(throwable)) {
            return false;
        }
        return throwable instanceof OpenAIIoException
                || throwable instanceof OpenAiResponseException responseFailure
                        && responseFailure.startsConfiguredProviderBackoff()
                || isServerError(throwable);
    }

    private void requireConfiguredProviderAdmission() {
        if (isConfiguredProviderInBackoff()) {
            log.warn("Configured provider unavailable (backoff active, providerId={})", CONFIGURED_PROVIDER.ordinal());
            throw new ConfiguredProviderTemporarilyUnavailableException(CONFIGURED_PROVIDER);
        }
        if (rateLimitService.tryReserveRequest(CONFIGURED_PROVIDER)) {
            return;
        }
        log.warn("Configured provider admission denied (providerId={})", CONFIGURED_PROVIDER.ordinal());
        throw new ConfiguredProviderTemporarilyUnavailableException(CONFIGURED_PROVIDER);
    }

    private boolean containsPermanentProviderFailure(Throwable throwable) {
        Set<Throwable> visitedFailures = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable failureCandidate = throwable;
        while (failureCandidate != null && visitedFailures.add(failureCandidate)) {
            if (failureCandidate instanceof OpenAIServiceException serviceException
                    && isPermanentProviderStatusCode(serviceException.statusCode())) {
                return true;
            }
            failureCandidate = failureCandidate.getCause();
        }
        return false;
    }

    private static boolean isPermanentProviderStatusCode(int statusCode) {
        return statusCode == HTTP_UNAUTHORIZED || statusCode == HTTP_FORBIDDEN || statusCode == HTTP_NOT_FOUND;
    }

    private boolean containsOkHttpCallTimeout(Throwable throwable) {
        Set<Throwable> visitedFailures = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable timeoutCandidate = throwable;
        while (timeoutCandidate != null && visitedFailures.add(timeoutCandidate)) {
            if (timeoutCandidate instanceof InterruptedIOException interruptedIoException
                    && isOkHttpCallTimeout(interruptedIoException)) {
                return true;
            }
            timeoutCandidate = timeoutCandidate.getCause();
        }
        return false;
    }

    private boolean isServerError(Throwable throwable) {
        return throwable instanceof OpenAIServiceException serviceException
                && serviceException.statusCode() >= HTTP_INTERNAL_SERVER_ERROR;
    }

    private boolean isCallerCancellation(Throwable throwable) {
        Set<Throwable> visitedFailures = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable cancellationCandidate = throwable;
        while (cancellationCandidate != null && visitedFailures.add(cancellationCandidate)) {
            if (cancellationCandidate instanceof InterruptedException) {
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

    private synchronized boolean isConfiguredProviderInBackoff() {
        return instantSource.instant().isBefore(configuredProviderBackoffUntil);
    }

    private synchronized void markConfiguredProviderBackoff() {
        Instant failureObservedAt = instantSource.instant();
        if (!failureObservedAt.isBefore(configuredProviderBackoffUntil)) {
            configuredProviderBackoffUntil = failureObservedAt.plus(configuredProviderBackoff.duration());
        }
        long backoffSecondsRemaining = Math.max(
                1L,
                Duration.between(failureObservedAt, configuredProviderBackoffUntil)
                        .toSeconds());
        log.warn("Configured provider temporarily disabled for {}s due to failure", backoffSecondsRemaining);
    }
}
