package com.williamcallahan.javachat.web;

import static com.williamcallahan.javachat.web.SseConstants.STATUS_CODE_STREAM_PROVIDER_FALLBACK;
import static com.williamcallahan.javachat.web.SseConstants.STATUS_STAGE_STREAM;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
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
    private static final String INITIAL_PROVIDER_NAME = "github_models";
    private static final String FALLBACK_PROVIDER_NAME = "openai";

    @Autowired
    MockMvc mockMvc;

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
    void chatStreamPublishesFallbackProviderAndNoticeBeforeFallbackText() throws Exception {
        given(chatMemoryService.getHistory(CHAT_SESSION_ID)).willReturn(List.of());
        given(chatService.buildStructuredPromptWithContextOutcome(
                        anyList(), eq(USER_QUERY), eq(ModelConfiguration.DEFAULT_MODEL)))
                .willReturn(new ChatService.StructuredPromptOutcome(
                        StructuredPrompt.fromRawPrompt("test", 1), List.of(), List.of()));
        given(retrievalService.toCitations(anyList())).willReturn(new RetrievalService.CitationOutcome(List.of(), 0));
        given(openAIStreamingService.isAvailable()).willReturn(true);
        StreamingResult providerFallbackStreamingResult = providerFallbackStreamingResult();
        given(openAIStreamingService.streamResponse(any(StructuredPrompt.class), anyDouble()))
                .willReturn(Mono.just(providerFallbackStreamingResult));

        String serializedSseStream = serializeSseResponse(
                "/api/chat/stream", "{\"sessionId\":\"" + CHAT_SESSION_ID + "\",\"message\":\"" + USER_QUERY + "\"}");

        assertFallbackProviderProtocol(serializedSseStream);
    }

    @Test
    void guidedStreamPublishesFallbackProviderAndNoticeBeforeFallbackText() throws Exception {
        given(chatMemoryService.getHistory(GUIDED_SESSION_ID)).willReturn(List.of());
        given(guidedLearningService.buildStructuredGuidedPromptWithContext(anyList(), eq(LESSON_SLUG), eq(USER_QUERY)))
                .willReturn(new GuidedLearningService.GuidedChatPromptOutcome(
                        StructuredPrompt.fromRawPrompt("test", 1), List.of()));
        given(guidedLearningService.citationsForBookDocuments(anyList())).willReturn(List.of());
        given(openAIStreamingService.isAvailable()).willReturn(true);
        StreamingResult providerFallbackStreamingResult = providerFallbackStreamingResult();
        given(openAIStreamingService.streamResponse(any(StructuredPrompt.class), anyDouble()))
                .willReturn(Mono.just(providerFallbackStreamingResult));

        String serializedSseStream = serializeSseResponse(
                "/api/guided/stream",
                "{\"sessionId\":\"" + GUIDED_SESSION_ID + "\",\"slug\":\"" + LESSON_SLUG + "\",\"latest\":\""
                        + USER_QUERY + "\"}");

        assertFallbackProviderProtocol(serializedSseStream);
    }

    private StreamingResult providerFallbackStreamingResult() {
        StreamingNotice fallbackNotice = mock(StreamingNotice.class);
        given(fallbackNotice.summary()).willReturn("Retrying stream with provider");
        given(fallbackNotice.diagnosticContext()).willReturn("Retrying before response text was emitted.");
        given(fallbackNotice.code()).willReturn(STATUS_CODE_STREAM_PROVIDER_FALLBACK);
        given(fallbackNotice.retryable()).willReturn(true);
        given(fallbackNotice.provider()).willReturn(FALLBACK_PROVIDER_NAME);
        given(fallbackNotice.stage()).willReturn(STATUS_STAGE_STREAM);
        given(fallbackNotice.attempt()).willReturn(2);
        given(fallbackNotice.maxAttempts()).willReturn(2);

        return new StreamingResult(
                Flux.just(FALLBACK_RESPONSE_TEXT),
                RateLimitService.ApiProvider.GITHUB_MODELS,
                Flux.just(RateLimitService.ApiProvider.OPENAI),
                Flux.just(fallbackNotice));
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

    private static void assertFallbackProviderProtocol(String serializedSseStream) {
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
                fallbackProviderEventPosition < fallbackTextPosition,
                "Fallback provider must precede fallback text. Payload was:\n" + serializedSseStream);
        assertTrue(
                fallbackNoticePosition < fallbackTextPosition,
                "Fallback notice must precede fallback text. Payload was:\n" + serializedSseStream);
    }

    private static int providerEventPosition(String serializedSseStream, String providerName) {
        String compactProviderEvent = "event:provider\ndata:{\"provider\":\"" + providerName + "\"}";
        int compactEventPosition = serializedSseStream.indexOf(compactProviderEvent);
        if (compactEventPosition >= 0) {
            return compactEventPosition;
        }
        String spacedProviderEvent = "event: provider\ndata: {\"provider\":\"" + providerName + "\"}";
        return serializedSseStream.indexOf(spacedProviderEvent);
    }
}
