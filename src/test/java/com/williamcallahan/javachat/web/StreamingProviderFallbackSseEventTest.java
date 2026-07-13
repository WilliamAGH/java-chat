package com.williamcallahan.javachat.web;

import static com.williamcallahan.javachat.web.SseConstants.EVENT_PROVIDER;
import static com.williamcallahan.javachat.web.SseConstants.STATUS_CODE_STREAM_PROVIDER_FALLBACK;
import static com.williamcallahan.javachat.web.SseConstants.STATUS_STAGE_STREAM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.ModelConfiguration;
import com.williamcallahan.javachat.config.WebMvcConfig;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.ChatService;
import com.williamcallahan.javachat.service.GuidedLearningService;
import com.williamcallahan.javachat.service.MarkdownService;
import com.williamcallahan.javachat.service.OpenAIStreamingService;
import com.williamcallahan.javachat.service.RateLimitService;
import com.williamcallahan.javachat.service.RetrievalService;
import com.williamcallahan.javachat.service.StreamingNotice;
import com.williamcallahan.javachat.service.StreamingResult;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Verifies both chat SSE endpoints publish the provider selected after a pre-text fallback. */
@WebMvcTest(controllers = {ChatController.class, GuidedLearningController.class})
@Import({AppProperties.class, WebMvcConfig.class, SseSupport.class})
@org.springframework.security.test.context.support.WithMockUser
class StreamingProviderFallbackSseEventTest {
    private static final String CHAT_SESSION_ID = "chat-provider-fallback";
    private static final String GUIDED_SESSION_ID = "guided-provider-fallback";
    private static final String LESSON_SLUG = "sealed-classes";
    private static final String USER_QUERY = "explain provider fallback";
    private static final String FALLBACK_RESPONSE_TEXT = "fallback provider response";
    private static final String INITIAL_PROVIDER_NAME = RateLimitService.ApiProvider.GITHUB_MODELS.getName();
    private static final String FALLBACK_PROVIDER_NAME = RateLimitService.ApiProvider.OPENAI.getName();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    SseSupport sseSupport;

    @MockitoBean
    ChatService chatService;

    @MockitoBean
    ChatMemoryService chatMemoryService;

    @MockitoBean
    GuidedLearningService guidedLearningService;

    @MockitoBean
    MarkdownService markdownService;

    @MockitoBean
    OpenAIStreamingService openAIStreamingService;

    @MockitoBean
    RetrievalService retrievalService;

    @MockitoBean
    ExceptionResponseBuilder exceptionResponseBuilder;

    @Test
    void chatStreamConsumesReplayableFallbackNoticeOnceAndPublishesProviderAndStatusBeforeText() throws Exception {
        given(chatMemoryService.getHistory(CHAT_SESSION_ID)).willReturn(List.of());
        given(chatService.buildStructuredPromptWithContextOutcome(
                        anyList(), eq(USER_QUERY), eq(ModelConfiguration.DEFAULT_MODEL)))
                .willReturn(new ChatService.StructuredPromptOutcome(
                        StructuredPrompt.fromRawPrompt("test", 1), List.of(), List.of()));
        given(retrievalService.toCitations(anyList())).willReturn(new RetrievalService.CitationOutcome(List.of(), 0));
        given(openAIStreamingService.isAvailable()).willReturn(true);
        AtomicInteger noticeSubscriptionCount = new AtomicInteger();
        StreamingResult providerFallbackStreamingResult = providerFallbackStreamingResult(noticeSubscriptionCount);
        given(openAIStreamingService.streamResponse(any(StructuredPrompt.class), anyDouble()))
                .willReturn(Mono.just(providerFallbackStreamingResult));

        String serializedSseStream = serializeSseResponse(
                "/api/chat/stream", "{\"sessionId\":\"" + CHAT_SESSION_ID + "\",\"message\":\"" + USER_QUERY + "\"}");

        assertFallbackProviderProtocol(serializedSseStream);
        verify(chatMemoryService).addExchange(CHAT_SESSION_ID, USER_QUERY, FALLBACK_RESPONSE_TEXT);
        assertEquals(
                1,
                noticeSubscriptionCount.get(),
                "ChatController must delegate the replayed notice to one SseSupport subscription");
    }

    @Test
    void guidedStreamConsumesReplayableFallbackNoticeOnceAndPublishesProviderAndStatusBeforeText() throws Exception {
        given(chatMemoryService.getHistory(GUIDED_SESSION_ID)).willReturn(List.of());
        given(guidedLearningService.buildStructuredGuidedPromptWithContext(anyList(), eq(LESSON_SLUG), eq(USER_QUERY)))
                .willReturn(new GuidedLearningService.GuidedChatPromptOutcome(
                        StructuredPrompt.fromRawPrompt("test", 1), List.of()));
        given(guidedLearningService.citationsForBookDocuments(anyList())).willReturn(List.of());
        given(openAIStreamingService.isAvailable()).willReturn(true);
        AtomicInteger noticeSubscriptionCount = new AtomicInteger();
        StreamingResult providerFallbackStreamingResult = providerFallbackStreamingResult(noticeSubscriptionCount);
        given(openAIStreamingService.streamResponse(any(StructuredPrompt.class), anyDouble()))
                .willReturn(Mono.just(providerFallbackStreamingResult));

        String serializedSseStream = serializeSseResponse(
                "/api/guided/stream",
                "{\"sessionId\":\"" + GUIDED_SESSION_ID + "\",\"slug\":\"" + LESSON_SLUG + "\",\"latest\":\""
                        + USER_QUERY + "\"}");

        assertFallbackProviderProtocol(serializedSseStream);
        verify(chatMemoryService).addExchange(GUIDED_SESSION_ID, USER_QUERY, FALLBACK_RESPONSE_TEXT);
        assertEquals(
                1,
                noticeSubscriptionCount.get(),
                "GuidedLearningController must delegate the replayed notice to one SseSupport subscription");
    }

    private StreamingResult providerFallbackStreamingResult(AtomicInteger noticeSubscriptionCount) {
        StreamingNotice fallbackNotice = mock(StreamingNotice.class);
        given(fallbackNotice.summary()).willReturn("Retrying stream with provider");
        given(fallbackNotice.diagnosticContext()).willReturn("Retrying before response text was emitted.");
        given(fallbackNotice.code()).willReturn(STATUS_CODE_STREAM_PROVIDER_FALLBACK);
        given(fallbackNotice.retryable()).willReturn(true);
        given(fallbackNotice.provider()).willReturn(FALLBACK_PROVIDER_NAME);
        given(fallbackNotice.stage()).willReturn(STATUS_STAGE_STREAM);
        given(fallbackNotice.attempt()).willReturn(2);
        given(fallbackNotice.maxAttempts()).willReturn(2);

        Sinks.Many<StreamingNotice> replayedNoticeSink = Sinks.many().replay().limit(1);
        assertEquals(Sinks.EmitResult.OK, replayedNoticeSink.tryEmitNext(fallbackNotice));
        assertEquals(Sinks.EmitResult.OK, replayedNoticeSink.tryEmitComplete());

        Flux<StreamingNotice> replayedNoticeFlux = Flux.defer(() -> {
            noticeSubscriptionCount.incrementAndGet();
            return replayedNoticeSink.asFlux();
        });
        return new StreamingResult(
                Flux.just(FALLBACK_RESPONSE_TEXT), RateLimitService.ApiProvider.GITHUB_MODELS, replayedNoticeFlux);
    }

    private String serializeSseResponse(String endpoint, String requestJson) throws Exception {
        MvcResult asynchronousStream = mockMvc.perform(post(endpoint)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(request().asyncStarted())
                .andReturn();

        return mockMvc.perform(asyncDispatch(asynchronousStream))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private void assertFallbackProviderProtocol(String serializedSseStream) {
        int initialProviderEventPosition = providerEventPosition(serializedSseStream, INITIAL_PROVIDER_NAME);
        int fallbackProviderEventPosition = providerEventPosition(serializedSseStream, FALLBACK_PROVIDER_NAME);
        int fallbackNoticePosition =
                serializedSseStream.indexOf("\"code\":\"" + STATUS_CODE_STREAM_PROVIDER_FALLBACK + "\"");
        int fallbackTextPosition = serializedSseStream.indexOf("\"text\":\"" + FALLBACK_RESPONSE_TEXT + "\"");

        assertTrue(
                initialProviderEventPosition >= 0,
                "Initial provider event is missing. Payload was:\n" + serializedSseStream);
        assertTrue(
                fallbackProviderEventPosition >= 0,
                "Fallback provider event is missing. Payload was:\n" + serializedSseStream);
        assertTrue(fallbackNoticePosition >= 0, "Fallback notice is missing. Payload was:\n" + serializedSseStream);
        assertTrue(fallbackTextPosition >= 0, "Fallback text event is missing. Payload was:\n" + serializedSseStream);
        assertTrue(
                initialProviderEventPosition < fallbackProviderEventPosition,
                "Initial provider must precede fallback provider. Payload was:\n" + serializedSseStream);
        assertTrue(
                fallbackProviderEventPosition < fallbackNoticePosition,
                "Fallback provider must precede fallback status. Payload was:\n" + serializedSseStream);
        assertTrue(
                fallbackProviderEventPosition < fallbackTextPosition,
                "Fallback provider must precede fallback text. Payload was:\n" + serializedSseStream);
        assertTrue(
                fallbackNoticePosition < fallbackTextPosition,
                "Fallback notice must precede fallback text. Payload was:\n" + serializedSseStream);
    }

    private int providerEventPosition(String serializedSseStream, String providerName) {
        String providerPayload = sseSupport.jsonSerialize(new SseSupport.ProviderPayload(providerName));
        String compactProviderEvent = "event:" + EVENT_PROVIDER + "\ndata:" + providerPayload;
        int compactEventPosition = serializedSseStream.indexOf(compactProviderEvent);
        if (compactEventPosition >= 0) {
            return compactEventPosition;
        }
        String spacedProviderEvent = "event: " + EVENT_PROVIDER + "\ndata: " + providerPayload;
        return serializedSseStream.indexOf(spacedProviderEvent);
    }
}
