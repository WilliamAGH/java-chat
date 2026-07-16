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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.openai.models.ErrorObject;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextDeltaEvent;
import com.openai.services.blocking.ResponseService;
import com.williamcallahan.javachat.adapters.out.llm.openai.OpenAiStreamingFailureException;
import com.williamcallahan.javachat.adapters.out.llm.openai.OpenAiStreamingFailureReporter;
import com.williamcallahan.javachat.application.prompt.PromptTruncator;
import com.williamcallahan.javachat.application.streaming.StreamingFailureReporter;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Verifies terminal provider transport failures, reservation enforcement, and completion validation.
 *
 * <p>Provider routing policy is verified in {@link OpenAiProviderRoutingServiceTest} so this
 * suite stays focused on SDK transport and stream-facing behavior.</p>
 */
class OpenAIStreamingServiceTest {
    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(OpenAIStreamingService.class);
    private final ListAppender<ILoggingEvent> serviceLogEvents = new ListAppender<>();
    private final Logger streamingFailureLogger =
            (Logger) LoggerFactory.getLogger(OpenAiStreamingFailureException.class);
    private final ListAppender<ILoggingEvent> streamingFailureLogEvents = new ListAppender<>();

    @BeforeEach
    void captureServiceLogs() {
        serviceLogEvents.start();
        serviceLogger.addAppender(serviceLogEvents);
        streamingFailureLogEvents.start();
        streamingFailureLogger.addAppender(streamingFailureLogEvents);
    }

    @AfterEach
    void stopCapturingServiceLogs() {
        serviceLogger.detachAppender(serviceLogEvents);
        serviceLogEvents.stop();
        serviceLogEvents.list.clear();
        streamingFailureLogger.detachAppender(streamingFailureLogEvents);
        streamingFailureLogEvents.stop();
        streamingFailureLogEvents.list.clear();
    }

    private OpenAIStreamingService createStreamingService() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        OpenAiRequestFactory requestFactory =
                new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gpt-5.2", "gpt-5", "");
        OpenAiProviderRoutingService providerRoutingService = new OpenAiProviderRoutingService(
                rateLimitService, 600, RateLimitService.ApiProvider.GITHUB_MODELS.getName());
        return new OpenAIStreamingService(
                rateLimitService, requestFactory, providerRoutingService, new OpenAiStreamingFailureReporter());
    }

    @Test
    void recoverableStreamingFailureUnwrapsNestedTerminalContext() {
        OpenAIStreamingService streamingService = createStreamingService();
        InternalServerException internalServerException = InternalServerException.builder()
                .statusCode(503)
                .headers(Headers.builder().build())
                .build();
        OpenAiStreamingFailureException terminalFailure = OpenAiStreamingFailureException.terminalAndLog(
                internalServerException,
                new StreamingFailureReporter.TerminalAttempt(
                        RateLimitService.ApiProvider.OPENAI.getName(), "gpt-5.2", 1, 1, false));

        assertTrue(streamingService.isRecoverableStreamingFailure(
                new IllegalStateException("reactor boundary", terminalFailure)));
    }

    @Test
    void subscribedTerminalStreamFailureLogsOneBoundedAlert() {
        String upstreamSecretBody = "OPENAI_API_KEY=secret-body";
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.isProviderAvailable(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        OpenAiRequestFactory requestFactory =
                new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gpt-5.2", "gpt-5", "");
        OpenAiProviderRoutingService providerRoutingService = new OpenAiProviderRoutingService(
                rateLimitService, 600, RateLimitService.ApiProvider.GITHUB_MODELS.getName());
        OpenAIStreamingService streamingService = new OpenAIStreamingService(
                rateLimitService, requestFactory, providerRoutingService, new OpenAiStreamingFailureReporter());
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
                    assertSame(upstreamFailure, terminalFailure.upstreamFailure());
                })
                .verify();

        verify(responseService).createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class));
        List<ILoggingEvent> terminalAlerts = streamingFailureLogEvents.list.stream()
                .filter(loggingEvent -> loggingEvent.getLevel() == Level.ERROR)
                .toList();
        assertEquals(1, terminalAlerts.size());
        ILoggingEvent terminalAlert = terminalAlerts.getFirst();
        assertNull(terminalAlert.getThrowableProxy());
        assertFalse(terminalAlert.getFormattedMessage().contains(upstreamSecretBody));
        assertFalse(terminalAlert.toString().contains(upstreamSecretBody));
    }

    @Test
    void emptyTextDeltaBeforeStreamingFailureIsTerminal() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.isProviderAvailable(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        OpenAiRequestFactory requestFactory =
                new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gpt-5.2", "gpt-5", "");
        OpenAiProviderRoutingService providerRoutingService = new OpenAiProviderRoutingService(
                rateLimitService, 600, RateLimitService.ApiProvider.GITHUB_MODELS.getName());
        OpenAIStreamingService streamingService = new OpenAIStreamingService(
                rateLimitService, requestFactory, providerRoutingService, new OpenAiStreamingFailureReporter());
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
                    assertSame(upstreamFailure, terminalFailure.upstreamFailure());
                })
                .verify();

        verify(responseService).createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class));
        verify(emptyTextDelta).delta();
    }

    @Test
    void deniedPrimaryReservationTerminatesStreamingAndCompletionBeforeAnyClientDispatch() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.isProviderAvailable(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        when(rateLimitService.isProviderAvailable(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(false);
        OpenAiRequestFactory requestFactory =
                new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gpt-5.2", "gpt-5", "");
        OpenAiProviderRoutingService providerRoutingService = new OpenAiProviderRoutingService(
                rateLimitService, 600, RateLimitService.ApiProvider.GITHUB_MODELS.getName());
        OpenAIStreamingService streamingService = new OpenAIStreamingService(
                rateLimitService, requestFactory, providerRoutingService, new OpenAiStreamingFailureReporter());
        OpenAIClient githubModelsClient = mock(OpenAIClient.class);
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        ReflectionTestUtils.setField(streamingService, "clientPrimary", githubModelsClient);
        ReflectionTestUtils.setField(streamingService, "clientSecondary", openAiClient);

        StepVerifier.create(streamingService
                        .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                        .flatMapMany(StreamingResult::textChunks))
                .expectError(IllegalStateException.class)
                .verify();
        StepVerifier.create(streamingService.complete("test", 0.7))
                .expectError(IllegalStateException.class)
                .verify();

        verify(rateLimitService, times(2)).tryReserveRequest(RateLimitService.ApiProvider.GITHUB_MODELS);
        verify(rateLimitService, never()).tryReserveRequest(RateLimitService.ApiProvider.OPENAI);
        verifyNoInteractions(githubModelsClient, openAiClient);
    }

    @Test
    void preTextStreamingTransportFailureIsTerminalAndDoesNotDispatchSecondaryProvider() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.isProviderAvailable(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        when(rateLimitService.isProviderAvailable(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        OpenAiRequestFactory requestFactory =
                new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gpt-5.2", "gpt-5", "");
        OpenAiProviderRoutingService providerRoutingService = new OpenAiProviderRoutingService(
                rateLimitService, 600, RateLimitService.ApiProvider.GITHUB_MODELS.getName());
        OpenAIStreamingService streamingService = new OpenAIStreamingService(
                rateLimitService, requestFactory, providerRoutingService, new OpenAiStreamingFailureReporter());
        OpenAIClient githubModelsClient = mock(OpenAIClient.class);
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        ResponseService githubModelsResponseService = mock(ResponseService.class);
        InternalServerException githubModelsFailure = InternalServerException.builder()
                .statusCode(504)
                .headers(Headers.builder().build())
                .build();
        when(githubModelsClient.responses()).thenReturn(githubModelsResponseService);
        when(githubModelsResponseService.createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenThrow(githubModelsFailure);
        ReflectionTestUtils.setField(streamingService, "clientPrimary", githubModelsClient);
        ReflectionTestUtils.setField(streamingService, "clientSecondary", openAiClient);

        StreamingResult streamingResult = Objects.requireNonNull(streamingService
                .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                .block());

        assertEquals(RateLimitService.ApiProvider.GITHUB_MODELS, streamingResult.provider());
        StepVerifier.create(streamingResult.textChunks())
                .expectErrorSatisfies(failure -> {
                    OpenAiStreamingFailureException terminalFailure =
                            assertInstanceOf(OpenAiStreamingFailureException.class, failure);
                    assertSame(githubModelsFailure, terminalFailure.upstreamFailure());
                })
                .verify();
        StepVerifier.create(streamingResult.notices()).verifyComplete();
        verify(githubModelsResponseService).createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class));
        verify(rateLimitService).tryReserveRequest(RateLimitService.ApiProvider.GITHUB_MODELS);
        verify(rateLimitService, never()).tryReserveRequest(RateLimitService.ApiProvider.OPENAI);
        verifyNoInteractions(openAiClient);
    }

    @Test
    void completionTransportFailureIsTerminalAndDoesNotDispatchSecondaryProvider() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.isProviderAvailable(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        when(rateLimitService.isProviderAvailable(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        OpenAiRequestFactory requestFactory =
                new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gpt-5.2", "gpt-5", "");
        OpenAiProviderRoutingService providerRoutingService = new OpenAiProviderRoutingService(
                rateLimitService, 600, RateLimitService.ApiProvider.GITHUB_MODELS.getName());
        OpenAIStreamingService streamingService = new OpenAIStreamingService(
                rateLimitService, requestFactory, providerRoutingService, new OpenAiStreamingFailureReporter());
        OpenAIClient githubModelsClient = mock(OpenAIClient.class);
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        ResponseService githubModelsResponseService = mock(ResponseService.class);
        InternalServerException githubModelsFailure = InternalServerException.builder()
                .statusCode(504)
                .headers(Headers.builder().build())
                .build();
        when(githubModelsClient.responses()).thenReturn(githubModelsResponseService);
        when(githubModelsResponseService.create(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenThrow(githubModelsFailure);
        ReflectionTestUtils.setField(streamingService, "clientPrimary", githubModelsClient);
        ReflectionTestUtils.setField(streamingService, "clientSecondary", openAiClient);

        StepVerifier.create(streamingService.complete("test", 0.7))
                .expectErrorSatisfies(failure -> {
                    assertSame(githubModelsFailure, failure);
                })
                .verify();

        verify(githubModelsResponseService).create(any(ResponseCreateParams.class), any(RequestOptions.class));
        verify(rateLimitService).tryReserveRequest(RateLimitService.ApiProvider.GITHUB_MODELS);
        verify(rateLimitService, never()).tryReserveRequest(RateLimitService.ApiProvider.OPENAI);
        verifyNoInteractions(openAiClient);
    }

    @Test
    void streamingFailureAfterTextIsTerminal() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.isProviderAvailable(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        OpenAiRequestFactory requestFactory =
                new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gpt-5.2", "gpt-5", "");
        OpenAiProviderRoutingService providerRoutingService = new OpenAiProviderRoutingService(
                rateLimitService, 600, RateLimitService.ApiProvider.GITHUB_MODELS.getName());
        OpenAIStreamingService streamingService = new OpenAIStreamingService(
                rateLimitService, requestFactory, providerRoutingService, new OpenAiStreamingFailureReporter());
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
                    assertSame(upstreamFailure, terminalFailure.upstreamFailure());
                })
                .verify();

        verify(responseService).createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class));
        verify(visibleTextDelta).delta();
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

    @Test
    void invalidCompletionConfigurationFailsOnSubscription() {
        OpenAIStreamingService streamingService = createStreamingService();

        assertCompletionFailure(streamingService.complete("prompt", 0.7, 0), "maximumOutputTokens must be positive");
        assertCompletionFailure(streamingService.complete("prompt", 0.7, -1), "maximumOutputTokens must be positive");
        assertCompletionFailure(
                streamingService.completeJsonObject("prompt", 0.7, 128, null), "requestTimeout must be positive");
        assertCompletionFailure(
                streamingService.completeJsonObject("prompt", 0.7, 128, Duration.ZERO),
                "requestTimeout must be positive");
        assertCompletionFailure(
                streamingService.completeJsonObject("prompt", 0.7, 128, Duration.ofSeconds(-1)),
                "requestTimeout must be positive");
    }

    private static void assertCompletionFailure(Mono<String> completion, String expectedFailureMessage) {
        StepVerifier.create(completion)
                .expectErrorMatches(failure -> failure instanceof IllegalArgumentException
                        && expectedFailureMessage.equals(failure.getMessage()))
                .verify();
    }

    private long logCount(Level level, String messageFragment) {
        return serviceLogEvents.list.stream()
                .filter(loggingEvent -> loggingEvent.getLevel().equals(level))
                .filter(loggingEvent -> loggingEvent.getFormattedMessage().contains(messageFragment))
                .count();
    }
}
