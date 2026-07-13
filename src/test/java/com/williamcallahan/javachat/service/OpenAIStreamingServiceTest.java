package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.openai.client.OpenAIClient;
import com.openai.core.http.Headers;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.williamcallahan.javachat.application.prompt.PromptTruncator;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import java.io.InterruptedIOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.Exceptions;

/**
 * Verifies primary-provider backoff and streaming failure classification.
 *
 * <p>Backoff tests target {@link OpenAiProviderRoutingService#shouldBackoffPrimary} directly
 * (package-private, same package). Streaming recovery tests exercise the public
 * {@link OpenAIStreamingService#isRecoverableStreamingFailure} API that delegates to
 * the routing service.</p>
 */
class OpenAIStreamingServiceTest {
    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(OpenAIStreamingService.class);
    private final ListAppender<ILoggingEvent> serviceLogEvents = new ListAppender<>();

    @BeforeEach
    void captureServiceLogs() {
        serviceLogEvents.start();
        serviceLogger.addAppender(serviceLogEvents);
    }

    @AfterEach
    void stopCapturingServiceLogs() {
        serviceLogger.detachAppender(serviceLogEvents);
        serviceLogEvents.stop();
        serviceLogEvents.list.clear();
    }

    private OpenAiProviderRoutingService createRoutingService() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        return new OpenAiProviderRoutingService(rateLimitService, 600, "github_models");
    }

    private OpenAIStreamingService createStreamingService() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        OpenAiRequestFactory requestFactory =
                new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gpt-5.2", "gpt-5", "");
        OpenAiProviderRoutingService providerRoutingService =
                new OpenAiProviderRoutingService(rateLimitService, 600, "github_models");
        return new OpenAIStreamingService(rateLimitService, requestFactory, providerRoutingService);
    }

    @Test
    void shouldBackoffPrimaryTreatsSdkIoAsBackoffEligible() {
        OpenAiProviderRoutingService routingService = createRoutingService();
        assertTrue(routingService.shouldBackoffPrimary(new OpenAIIoException("io")));
    }

    @Test
    void shouldBackoffPrimaryIgnoresCallerCancellationWrappedBySdkIo() {
        OpenAiProviderRoutingService routingService = createRoutingService();
        InterruptedIOException interruptedRequest = new InterruptedIOException("request interrupted by caller timeout");
        OpenAIIoException cancelledCompletion = new OpenAIIoException("Request failed", interruptedRequest);

        assertFalse(routingService.shouldBackoffPrimary(cancelledCompletion));
    }

    @Test
    void callerCancellationKeepsConfiguredPrimaryProviderEligible() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.isProviderAvailable(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        when(rateLimitService.isProviderAvailable(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        OpenAiProviderRoutingService routingService = new OpenAiProviderRoutingService(rateLimitService, 600, "openai");
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        OpenAIClient githubModelsClient = mock(OpenAIClient.class);
        InterruptedIOException interruptedRequest = new InterruptedIOException("request interrupted by caller timeout");
        OpenAIIoException cancelledCompletion = new OpenAIIoException("Request failed", interruptedRequest);

        routingService.recordProviderFailure(RateLimitService.ApiProvider.OPENAI, cancelledCompletion);

        List<OpenAiProviderCandidate> availableProviders =
                routingService.selectAvailableProviderCandidates(githubModelsClient, openAiClient);
        assertEquals(2, availableProviders.size());
        assertEquals(
                RateLimitService.ApiProvider.OPENAI, availableProviders.get(0).provider());
    }

    @Test
    void shouldBackoffPrimaryTreats401AsBackoffEligible() {
        OpenAiProviderRoutingService routingService = createRoutingService();
        Headers headers = Headers.builder().build();
        UnauthorizedException unauthorized =
                UnauthorizedException.builder().headers(headers).build();
        assertTrue(routingService.shouldBackoffPrimary(unauthorized));
    }

    @Test
    void shouldBackoffPrimaryTreats429AsBackoffEligible() {
        OpenAiProviderRoutingService routingService = createRoutingService();
        Headers headers = Headers.builder().build();
        RateLimitException rateLimit =
                RateLimitException.builder().headers(headers).build();
        assertTrue(routingService.shouldBackoffPrimary(rateLimit));
    }

    @Test
    void shouldBackoffPrimaryTreats404AsBackoffEligible() {
        OpenAiProviderRoutingService routingService = createRoutingService();
        Headers headers = Headers.builder().build();
        NotFoundException notFoundException =
                NotFoundException.builder().headers(headers).build();
        assertTrue(routingService.shouldBackoffPrimary(notFoundException));
    }

    @Test
    void shouldBackoffPrimaryDoesNotBackoffOnGenericRuntime() {
        OpenAiProviderRoutingService routingService = createRoutingService();
        assertFalse(routingService.shouldBackoffPrimary(new IllegalArgumentException("no")));
    }

    @Test
    void recoverableStreamingFailureTreatsReactorOverflowTypeAsRetryable() {
        OpenAIStreamingService streamingService = createStreamingService();
        assertTrue(streamingService.isRecoverableStreamingFailure(Exceptions.failWithOverflow()));
    }

    @Test
    void recoverableStreamingFailureTreatsValidationErrorsAsNonRetryable() {
        OpenAIStreamingService streamingService = createStreamingService();
        assertFalse(
                streamingService.isRecoverableStreamingFailure(new IllegalArgumentException("bad request payload")));
    }

    @Test
    void recoverableStreamingFailureTreatsNotFoundAsRetryable() {
        OpenAIStreamingService streamingService = createStreamingService();
        Headers headers = Headers.builder().build();
        NotFoundException notFoundException =
                NotFoundException.builder().headers(headers).build();
        assertTrue(streamingService.isRecoverableStreamingFailure(notFoundException));
    }

    @Test
    void primaryBackoffDoesNotHideOnlyConfiguredProvider() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.isProviderAvailable(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        OpenAiProviderRoutingService routingService = new OpenAiProviderRoutingService(rateLimitService, 600, "openai");
        OpenAIClient openAiClient = mock(OpenAIClient.class);

        routingService.recordProviderFailure(RateLimitService.ApiProvider.OPENAI, new OpenAIIoException("io"));

        List<OpenAiProviderCandidate> availableProviders =
                routingService.selectAvailableProviderCandidates(null, openAiClient);

        assertEquals(1, availableProviders.size());
        assertEquals(
                RateLimitService.ApiProvider.OPENAI, availableProviders.get(0).provider());
    }

    @Test
    void primaryBackoffUsesConfiguredAlternateProviderWhenAvailable() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.isProviderAvailable(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        when(rateLimitService.isProviderAvailable(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        OpenAiProviderRoutingService routingService = new OpenAiProviderRoutingService(rateLimitService, 600, "openai");
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        OpenAIClient githubModelsClient = mock(OpenAIClient.class);

        routingService.recordProviderFailure(RateLimitService.ApiProvider.OPENAI, new OpenAIIoException("io"));

        List<OpenAiProviderCandidate> availableProviders =
                routingService.selectAvailableProviderCandidates(githubModelsClient, openAiClient);

        assertEquals(1, availableProviders.size());
        assertEquals(
                RateLimitService.ApiProvider.GITHUB_MODELS,
                availableProviders.get(0).provider());
    }

    @Test
    void unavailableStreamDefersErrorSeverityToRequestBoundary() {
        OpenAIStreamingService streamingService = createStreamingService();

        assertThrows(IllegalStateException.class, () -> streamingService
                .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                .block());

        assertEquals(0, logCount(Level.ERROR, "LLM providers unavailable"));
        assertEquals(1, logCount(Level.WARN, "LLM providers unavailable"));
    }

    private long logCount(Level level, String messageFragment) {
        return serviceLogEvents.list.stream()
                .filter(loggingEvent -> loggingEvent.getLevel().equals(level))
                .filter(loggingEvent -> loggingEvent.getFormattedMessage().contains(messageFragment))
                .count();
    }
}
