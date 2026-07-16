package com.williamcallahan.javachat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.WebMvcConfig;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.ChatService;
import com.williamcallahan.javachat.service.OpenAIStreamingService;
import com.williamcallahan.javachat.service.RetrievalService;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies the chat stream request exposes one canonical, meaningful query. */
@WebMvcTest(controllers = ChatController.class)
@Import({AppProperties.class, WebMvcConfig.class})
@org.springframework.security.test.context.support.WithMockUser
class ChatStreamRequestTest {
    private static final String SESSION_ID = "request-session";
    private static final String USER_QUERY = "What is new in Java 25?";
    private static final String UNICODE_WHITESPACE_QUERY = "\u2003";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ChatService chatService;

    @MockitoBean
    ChatMemoryService chatMemoryService;

    @MockitoBean
    OpenAIStreamingService openAIStreamingService;

    @MockitoBean
    RetrievalService retrievalService;

    @MockitoBean
    SseSupport sseSupport;

    @MockitoBean
    ExceptionResponseBuilder exceptionResponseBuilder;

    @Test
    void retainsValidLatestQuery() {
        ChatStreamRequest request = new ChatStreamRequest(SESSION_ID, USER_QUERY);

        assertEquals(USER_QUERY, request.latest());
    }

    @Test
    void rejectsUnicodeWhitespaceLatestQuery() {
        assertThrows(IllegalArgumentException.class, () -> new ChatStreamRequest(SESSION_ID, UNICODE_WHITESPACE_QUERY));
    }

    @ParameterizedTest
    @MethodSource("invalidChatStreamRequestBodies")
    void rejectsMissingNullBlankAndLegacyMessageQueries(String invalidRequestBody) throws Exception {
        mockMvc.perform(post("/api/chat/stream")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidRequestBody))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(chatService, openAIStreamingService);
    }

    private static Stream<String> invalidChatStreamRequestBodies() {
        return Stream.of(
                "{\"sessionId\":\"request-session\"}",
                "{\"sessionId\":\"request-session\",\"latest\":null}",
                "{\"sessionId\":\"request-session\",\"latest\":\"\"}",
                "{\"sessionId\":\"request-session\",\"latest\":\"\u2003\"}",
                "{\"sessionId\":\"request-session\",\"message\":\"legacy query\"}");
    }
}
