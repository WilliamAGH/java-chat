package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.openai.client.OpenAIClient;
import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.StreamResponse;
import com.openai.errors.InternalServerException;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.models.ErrorObject;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextDeltaEvent;
import com.openai.services.blocking.ResponseService;
import com.williamcallahan.javachat.application.prompt.PromptTruncator;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.Exceptions;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

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
    void recoverableStreamingFailureUnwrapsNestedTerminalContext() {
        OpenAIStreamingService streamingService = createStreamingService();
        NotFoundException notFoundException =
                NotFoundException.builder().headers(Headers.builder().build()).build();
        ResponseCreateParams responseParameters =
                ResponseCreateParams.builder().input("test").model("gpt-5.2").build();
        OpenAiPreparedRequest preparedRequest = new OpenAiPreparedRequest(responseParameters, "gpt-5.2");
        OpenAiProviderCandidate providerCandidate =
                new OpenAiProviderCandidate(mock(OpenAIClient.class), RateLimitService.ApiProvider.OPENAI);
        StreamingAttemptContext attemptContext = StreamingAttemptContext.first(
                List.of(providerCandidate), Sinks.many().multicast().onBackpressureBuffer());
        OpenAiStreamingFailureException terminalFailure = OpenAiStreamingFailureException.terminalAndLog(
                notFoundException, preparedRequest, attemptContext, false);

        assertTrue(streamingService.isRecoverableStreamingFailure(
                new IllegalStateException("reactor boundary", terminalFailure)));
    }

    @Test
    void subscribedTerminalStreamFailureLogsOneBoundedAlert() {
        String upstreamSecretBody = "OPENAI_API_KEY=secret-body";
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.isProviderAvailable(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        OpenAiRequestFactory requestFactory =
                new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gpt-5.2", "gpt-5", "");
        OpenAiProviderRoutingService providerRoutingService =
                new OpenAiProviderRoutingService(rateLimitService, 600, "github_models");
        OpenAIStreamingService streamingService =
                new OpenAIStreamingService(rateLimitService, requestFactory, providerRoutingService);
        OpenAIClient githubModelsClient = mock(OpenAIClient.class);
        ResponseService responseService = mock(ResponseService.class);
        ErrorObject upstreamError = ErrorObject.builder()
                .message(upstreamSecretBody)
                .code("queue_upstream_timeout")
                .param(Optional.empty())
                .type("upstream_timeout")
                .build();
        InternalServerException upstreamFailure = InternalServerException.builder()
                .statusCode(504)
                .headers(Headers.builder().build())
                .error(upstreamError)
                .build();
        when(githubModelsClient.responses()).thenReturn(responseService);
        when(responseService.createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenThrow(upstreamFailure);
        ReflectionTestUtils.setField(streamingService, "clientPrimary", githubModelsClient);

        StepVerifier.create(streamingService
                        .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                        .flatMapMany(StreamingResult::textChunks))
                .expectErrorSatisfies(failure -> {
                    OpenAiStreamingFailureException terminalFailure =
                            assertInstanceOf(OpenAiStreamingFailureException.class, failure);
                    assertSame(upstreamFailure, terminalFailure.getCause());
                    assertEquals(2, terminalFailure.currentAttempt());
                    assertTrue(terminalFailure.beforeFirstChunk());
                })
                .verify();

        verify(responseService, times(2)).createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class));
        List<ILoggingEvent> terminalAlerts = serviceLogEvents.list.stream()
                .filter(loggingEvent -> loggingEvent.getLevel() == Level.ERROR)
                .filter(loggingEvent -> loggingEvent.getKeyValuePairs().stream()
                        .anyMatch(
                                logField -> "event".equals(logField.key) && "llm_stream_failed".equals(logField.value)))
                .toList();
        assertEquals(1, terminalAlerts.size());
        ILoggingEvent terminalAlert = terminalAlerts.getFirst();
        assertNull(terminalAlert.getThrowableProxy());
        assertFalse(terminalAlert.getFormattedMessage().contains(upstreamSecretBody));
        assertFalse(terminalAlert.toString().contains(upstreamSecretBody));
    }

    @Test
    void emptyTextDeltaDoesNotDisablePreTextRetry() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.isProviderAvailable(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        OpenAiRequestFactory requestFactory =
                new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gpt-5.2", "gpt-5", "");
        OpenAiProviderRoutingService providerRoutingService =
                new OpenAiProviderRoutingService(rateLimitService, 600, "github_models");
        OpenAIStreamingService streamingService =
                new OpenAIStreamingService(rateLimitService, requestFactory, providerRoutingService);
        OpenAIClient githubModelsClient = mock(OpenAIClient.class);
        ResponseService responseService = mock(ResponseService.class);
        StreamResponse<ResponseStreamEvent> providerStream = mock();
        ResponseStreamEvent emptyTextEvent = mock(ResponseStreamEvent.class);
        ResponseTextDeltaEvent emptyTextDelta = mock(ResponseTextDeltaEvent.class);
        InternalServerException upstreamFailure = InternalServerException.builder()
                .statusCode(504)
                .headers(Headers.builder().build())
                .build();
        when(githubModelsClient.responses()).thenReturn(responseService);
        when(responseService.createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(providerStream);
        when(emptyTextEvent.outputTextDelta()).thenReturn(Optional.of(emptyTextDelta));
        when(emptyTextDelta.delta()).thenReturn("");
        when(providerStream.stream())
                .thenAnswer(ignoredInvocation -> Stream.concat(Stream.of(emptyTextEvent), Stream.generate(() -> {
                    throw upstreamFailure;
                })));
        ReflectionTestUtils.setField(streamingService, "clientPrimary", githubModelsClient);

        StepVerifier.create(streamingService
                        .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                        .flatMapMany(StreamingResult::textChunks))
                .expectErrorSatisfies(failure -> {
                    OpenAiStreamingFailureException terminalFailure =
                            assertInstanceOf(OpenAiStreamingFailureException.class, failure);
                    assertSame(upstreamFailure, terminalFailure.getCause());
                    assertEquals(2, terminalFailure.currentAttempt());
                    assertTrue(terminalFailure.beforeFirstChunk());
                })
                .verify();

        verify(responseService, times(2)).createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class));
        verify(emptyTextDelta, times(2)).delta();
    }

    @Test
    void nonEmptyTextDeltaDisablesPreTextRetry() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.isProviderAvailable(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        OpenAiRequestFactory requestFactory =
                new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gpt-5.2", "gpt-5", "");
        OpenAiProviderRoutingService providerRoutingService =
                new OpenAiProviderRoutingService(rateLimitService, 600, "github_models");
        OpenAIStreamingService streamingService =
                new OpenAIStreamingService(rateLimitService, requestFactory, providerRoutingService);
        OpenAIClient githubModelsClient = mock(OpenAIClient.class);
        ResponseService responseService = mock(ResponseService.class);
        StreamResponse<ResponseStreamEvent> providerStream = mock();
        ResponseStreamEvent visibleTextEvent = mock(ResponseStreamEvent.class);
        ResponseStreamEvent failedStreamEvent = mock(ResponseStreamEvent.class);
        ResponseTextDeltaEvent visibleTextDelta = mock(ResponseTextDeltaEvent.class);
        InternalServerException upstreamFailure = InternalServerException.builder()
                .statusCode(504)
                .headers(Headers.builder().build())
                .build();
        when(githubModelsClient.responses()).thenReturn(responseService);
        when(responseService.createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(providerStream);
        when(visibleTextEvent.outputTextDelta()).thenReturn(Optional.of(visibleTextDelta));
        when(visibleTextDelta.delta()).thenReturn("first token");
        when(failedStreamEvent.outputTextDelta()).thenThrow(upstreamFailure);
        when(providerStream.stream()).thenAnswer(ignoredInvocation -> Stream.of(visibleTextEvent, failedStreamEvent));
        ReflectionTestUtils.setField(streamingService, "clientPrimary", githubModelsClient);

        StepVerifier.create(streamingService
                        .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                        .flatMapMany(StreamingResult::textChunks))
                .expectNext("first token")
                .expectErrorSatisfies(failure -> {
                    OpenAiStreamingFailureException terminalFailure =
                            assertInstanceOf(OpenAiStreamingFailureException.class, failure);
                    assertSame(upstreamFailure, terminalFailure.getCause());
                    assertEquals(1, terminalFailure.currentAttempt());
                    assertFalse(terminalFailure.beforeFirstChunk());
                })
                .verify();

        verify(responseService).createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class));
        verify(visibleTextDelta).delta();
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
