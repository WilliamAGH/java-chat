package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.services.async.ResponseServiceAsync;
import com.williamcallahan.javachat.adapters.out.llm.openai.OpenAiStreamingFailureException;
import com.williamcallahan.javachat.adapters.out.llm.openai.OpenAiStreamingFailureReporter;
import com.williamcallahan.javachat.config.ModelConfiguration;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Verifies that provider gateways remain the sole owner of streaming subphase deadlines. */
class OpenAIStreamingTimeoutContractTest {

    @Test
    void streamingReadDeadlineInheritsWholeRequestDeadline() {
        OpenAIClient gatewayClient = mock(OpenAIClient.class);
        StructuredPrompt structuredPrompt = StructuredPrompt.fromRawPrompt("Confirm the timeout budget", 6);
        OpenAiRequestFactory requestFactory = mock(OpenAiRequestFactory.class);
        when(requestFactory.prepareStreamingRequest(structuredPrompt, 0.4))
                .thenReturn(new OpenAiPreparedRequest(
                        ResponseCreateParams.builder()
                                .model(ModelConfiguration.DEFAULT_MODEL)
                                .input("Timeout proof")
                                .build(),
                        ModelConfiguration.DEFAULT_MODEL,
                        structuredPrompt));
        OpenAiProviderRoutingService providerRoutingService = mock(OpenAiProviderRoutingService.class);
        when(providerRoutingService.hasConfiguredProviderClient(gatewayClient)).thenReturn(true);
        when(providerRoutingService.configuredProvider()).thenReturn(RateLimitService.ApiProvider.OPENAI);
        when(providerRoutingService.admitConfiguredProviderRequest(gatewayClient))
                .thenReturn(
                        Optional.of(new OpenAiProviderCandidate(gatewayClient, RateLimitService.ApiProvider.OPENAI)));
        OpenAIStreamingService streamingService = new OpenAIStreamingService(
                mock(RateLimitService.class),
                requestFactory,
                providerRoutingService,
                new OpenAiStreamingFailureReporter());
        OpenAIClientAsync asyncClient = mock(OpenAIClientAsync.class);
        ResponseServiceAsync responseService = mock(ResponseServiceAsync.class);
        when(gatewayClient.async()).thenReturn(asyncClient);
        when(asyncClient.responses()).thenReturn(responseService);
        AtomicReference<RequestOptions> capturedRequest = new AtomicReference<>();
        when(responseService.createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenAnswer(invocation -> {
                    capturedRequest.set(invocation.getArgument(1, RequestOptions.class));
                    throw new IllegalStateException("Stop the timeout proof before network access");
                });
        ReflectionTestUtils.setField(streamingService, "openAiClient", gatewayClient);
        ReflectionTestUtils.setField(streamingService, "streamingRequestTimeoutSeconds", 600L);

        assertThrows(OpenAiStreamingFailureException.class, () -> streamingService
                .streamResponse(LlmGatewayTier.Purpose.CHAT_ANSWER, structuredPrompt, 0.4)
                .flatMapMany(StreamingResult::textChunks)
                .blockLast(Duration.ofSeconds(10)));

        RequestOptions gatewayRequest = capturedRequest.get();
        assertNotNull(gatewayRequest);
        Timeout requestTimeout =
                Objects.requireNonNull(gatewayRequest.getTimeout(), "Streaming requests must specify their timeout");
        assertEquals(Duration.ofSeconds(600), requestTimeout.request());
        assertEquals(requestTimeout.request(), requestTimeout.read());
    }
}
