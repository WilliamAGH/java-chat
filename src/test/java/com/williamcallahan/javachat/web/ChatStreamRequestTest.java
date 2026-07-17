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
import org.junit.jupiter.params.provider.Arguments;
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

    @ParameterizedTest(name = "{0} cannot be a latest query")
    @MethodSource("unicodeBlankLatestQueries")
    void rejectsLatestQueryWithoutVisibleContent(String unicodeDescription, String unicodeBlankQuery) {
        assertThrows(IllegalArgumentException.class, () -> new ChatStreamRequest(SESSION_ID, unicodeBlankQuery));
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

    private static Stream<Arguments> unicodeBlankLatestQueries() {
        return Stream.of(
                Arguments.of("U+00A0 NO-BREAK SPACE", "\u00A0"),
                Arguments.of("U+202F NARROW NO-BREAK SPACE", "\u202F"),
                Arguments.of("U+2003 EM SPACE", "\u2003"),
                Arguments.of("U+FEFF ZERO WIDTH NO-BREAK SPACE", "\uFEFF"),
                Arguments.of("U+200B ZERO WIDTH SPACE", "\u200B"),
                Arguments.of("U+2060 WORD JOINER", "\u2060"));
    }
}
