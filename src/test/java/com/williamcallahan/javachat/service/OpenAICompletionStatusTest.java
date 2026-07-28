package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.RequestOptions;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseError;
import com.openai.models.responses.ResponseStatus;
import com.openai.services.async.ResponseServiceAsync;
import com.williamcallahan.javachat.adapters.out.llm.openai.OpenAiStreamingFailureReporter;
import com.williamcallahan.javachat.application.prompt.PromptTruncator;
import com.williamcallahan.javachat.config.AppProperties;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.test.StepVerifier;

/** Verifies non-streaming Responses status controls text extraction and provider accounting. */
class OpenAICompletionStatusTest {
    private static final long CONFIGURED_PROVIDER_BACKOFF_SECONDS = 600L;
    private static final int TEST_COMPLETION_OUTPUT_TOKEN_BUDGET = 768;
    private static final int EXPECTED_PROVIDER_REQUEST_COUNT_AFTER_READMISSION = 2;

    @Test
    void completionRecordsSuccessOnlyAfterCompletedStatus() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        OpenAIStreamingService streamingService = configuredStreamingService(rateLimitService);
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        ResponseServiceAsync responseService = mockAsyncResponseService(openAiClient);
        CompletableFuture<Response> providerCompletionFuture = new CompletableFuture<>();
        Response providerCompletion = mock(Response.class);
        when(providerCompletion.status()).thenReturn(Optional.of(ResponseStatus.COMPLETED));
        when(providerCompletion.output()).thenReturn(List.of());
        when(responseService.create(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(providerCompletionFuture);
        ReflectionTestUtils.setField(streamingService, "openAiClient", openAiClient);

        StepVerifier.create(streamingService.complete("prompt", 0.7))
                .then(() -> providerCompletionFuture.complete(providerCompletion))
                .expectNext("")
                .verifyComplete();

        verify(rateLimitService).recordSuccess(RateLimitService.ApiProvider.OPENAI);
        verify(openAiClient, never()).responses();
    }

    @Test
    void completionRejectsFailedResponseWithoutRecordingSuccess() {
        assertCompletionFailure(new CompletionFailureScenario(
                completionWithStatus(ResponseStatus.FAILED),
                OpenAiResponseStreamException.TerminalReason.FAILED,
                false,
                false));
    }

    @Test
    void completionRejectsIncompleteResponseWithoutRecordingSuccess() {
        assertCompletionFailure(new CompletionFailureScenario(
                completionWithStatus(ResponseStatus.INCOMPLETE),
                OpenAiResponseStreamException.TerminalReason.INCOMPLETE,
                false,
                false));
    }

    @Test
    void completionRejectsInProgressResponseWithoutRecordingSuccess() {
        assertCompletionFailure(new CompletionFailureScenario(
                completionWithStatus(ResponseStatus.IN_PROGRESS),
                OpenAiResponseStreamException.TerminalReason.MISSING_COMPLETION,
                true,
                true));
    }

    @Test
    void completionRejectsQueuedResponseAsMissingCompletion() {
        assertCompletionFailure(new CompletionFailureScenario(
                completionWithStatus(ResponseStatus.QUEUED),
                OpenAiResponseStreamException.TerminalReason.MISSING_COMPLETION,
                true,
                true));
    }

    @Test
    void completionRejectsCancelledResponseWithoutProviderBackoff() {
        assertCompletionFailure(new CompletionFailureScenario(
                completionWithStatus(ResponseStatus.CANCELLED),
                OpenAiResponseStreamException.TerminalReason.CANCELLED,
                false,
                false));
    }

    @Test
    void completionPreservesFailedResponseCodesAndProviderBackoff() {
        List.of(
                        new CompletionFailureScenario(
                                failedCompletion(ResponseError.Code.SERVER_ERROR),
                                OpenAiResponseStreamException.TerminalReason.SERVER_ERROR,
                                true,
                                true),
                        new CompletionFailureScenario(
                                failedCompletion(ResponseError.Code.RATE_LIMIT_EXCEEDED),
                                OpenAiResponseStreamException.TerminalReason.RATE_LIMIT_EXCEEDED,
                                false,
                                true))
                .forEach(OpenAICompletionStatusTest::assertCompletionFailure);
    }

    @Test
    void completionPreservesIncompleteReasonsWithoutProviderBackoff() {
        List.of(
                        new CompletionFailureScenario(
                                incompleteCompletion(Response.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS),
                                OpenAiResponseStreamException.TerminalReason.MAX_OUTPUT_TOKENS,
                                false,
                                false),
                        new CompletionFailureScenario(
                                incompleteCompletion(Response.IncompleteDetails.Reason.CONTENT_FILTER),
                                OpenAiResponseStreamException.TerminalReason.CONTENT_FILTER,
                                false,
                                false))
                .forEach(OpenAICompletionStatusTest::assertCompletionFailure);
    }

    @Test
    void cancelledCompletionRetainsSdkFutureForLateOutcomeAccounting() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        OpenAIStreamingService streamingService = configuredStreamingService(rateLimitService);
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        ResponseServiceAsync responseService = mockAsyncResponseService(openAiClient);
        CompletableFuture<Response> providerCompletionFuture = new CompletableFuture<>();
        Response providerCompletion = mock(Response.class);
        when(providerCompletion.status()).thenReturn(Optional.of(ResponseStatus.COMPLETED));
        when(providerCompletion.output()).thenReturn(List.of());
        when(responseService.create(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(providerCompletionFuture);
        ReflectionTestUtils.setField(streamingService, "openAiClient", openAiClient);

        StepVerifier.create(streamingService.complete("prompt", 0.7))
                .thenCancel()
                .verify();

        assertFalse(providerCompletionFuture.isCancelled());
        providerCompletionFuture.complete(providerCompletion);
        verify(rateLimitService).recordSuccess(RateLimitService.ApiProvider.OPENAI);
    }

    private static void assertCompletionFailure(CompletionFailureScenario completionFailureScenario) {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        OpenAIStreamingService streamingService = configuredStreamingService(rateLimitService);
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        ResponseServiceAsync responseService = mockAsyncResponseService(openAiClient);
        Response completedCompletion = completionWithStatus(ResponseStatus.COMPLETED);
        when(completedCompletion.output()).thenReturn(List.of());
        when(responseService.create(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.completedFuture(completionFailureScenario.providerCompletion()))
                .thenReturn(CompletableFuture.completedFuture(completedCompletion));
        ReflectionTestUtils.setField(streamingService, "openAiClient", openAiClient);

        StepVerifier.create(streamingService.complete("prompt", 0.7))
                .expectErrorSatisfies(completionFailure -> {
                    OpenAiResponseStreamException terminalFailure =
                            assertInstanceOf(OpenAiResponseStreamException.class, completionFailure);
                    assertEquals(completionFailureScenario.expectedTerminalReason(), terminalFailure.terminalReason());
                    assertEquals(
                            completionFailureScenario.expectedRetryable(),
                            streamingService.isRecoverableStreamingFailure(terminalFailure));
                })
                .verify();

        verify(completionFailureScenario.providerCompletion(), never()).output();
        if (completionFailureScenario.expectsConfiguredProviderBackoff()) {
            StepVerifier.create(streamingService.complete("prompt", 0.7))
                    .expectError(ConfiguredProviderTemporarilyUnavailableException.class)
                    .verify();
            verify(responseService).create(any(ResponseCreateParams.class), any(RequestOptions.class));
            verify(rateLimitService, never()).recordSuccess(RateLimitService.ApiProvider.OPENAI);
            return;
        }

        StepVerifier.create(streamingService.complete("prompt", 0.7))
                .expectNext("")
                .verifyComplete();
        verify(responseService, times(EXPECTED_PROVIDER_REQUEST_COUNT_AFTER_READMISSION))
                .create(any(ResponseCreateParams.class), any(RequestOptions.class));
        verify(rateLimitService).recordSuccess(RateLimitService.ApiProvider.OPENAI);
    }

    private static Response completionWithStatus(ResponseStatus responseStatus) {
        Response providerCompletion = mock(Response.class);
        when(providerCompletion.status()).thenReturn(Optional.of(responseStatus));
        when(providerCompletion.error()).thenReturn(Optional.empty());
        when(providerCompletion.incompleteDetails()).thenReturn(Optional.empty());
        return providerCompletion;
    }

    private static Response failedCompletion(ResponseError.Code providerErrorCode) {
        Response providerCompletion = completionWithStatus(ResponseStatus.FAILED);
        ResponseError providerError = mock(ResponseError.class);
        when(providerError.code()).thenReturn(providerErrorCode);
        when(providerCompletion.error()).thenReturn(Optional.of(providerError));
        return providerCompletion;
    }

    private static Response incompleteCompletion(Response.IncompleteDetails.Reason incompleteReason) {
        Response providerCompletion = completionWithStatus(ResponseStatus.INCOMPLETE);
        Response.IncompleteDetails incompleteDetails = mock(Response.IncompleteDetails.class);
        when(incompleteDetails.reason()).thenReturn(Optional.of(incompleteReason));
        when(providerCompletion.incompleteDetails()).thenReturn(Optional.of(incompleteDetails));
        return providerCompletion;
    }

    private static OpenAIStreamingService configuredStreamingService(RateLimitService rateLimitService) {
        return new OpenAIStreamingService(
                rateLimitService,
                testRequestFactory(),
                new OpenAiProviderRoutingService(rateLimitService, configuredLlmProperties()),
                new OpenAiStreamingFailureReporter());
    }

    private static AppProperties configuredLlmProperties() {
        AppProperties appProperties = new AppProperties();
        appProperties.getLlm().setCompletionOutputTokenBudget(TEST_COMPLETION_OUTPUT_TOKEN_BUDGET);
        appProperties.getLlm().setConfiguredProviderBackoffSeconds(CONFIGURED_PROVIDER_BACKOFF_SECONDS);
        return appProperties;
    }

    private static OpenAiRequestFactory testRequestFactory() {
        return new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gpt-5.4", configuredLlmProperties());
    }

    private static ResponseServiceAsync mockAsyncResponseService(OpenAIClient openAiClient) {
        OpenAIClientAsync asyncClient = mock(OpenAIClientAsync.class);
        ResponseServiceAsync responseService = mock(ResponseServiceAsync.class);
        when(openAiClient.async()).thenReturn(asyncClient);
        when(asyncClient.responses()).thenReturn(responseService);
        return responseService;
    }

    private record CompletionFailureScenario(
            Response providerCompletion,
            OpenAiResponseStreamException.TerminalReason expectedTerminalReason,
            boolean expectedRetryable,
            boolean expectsConfiguredProviderBackoff) {}
}
