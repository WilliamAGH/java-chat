package com.williamcallahan.javachat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.ChatService;
import com.williamcallahan.javachat.service.OpenAIStreamingService;
import com.williamcallahan.javachat.service.RetrievalService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/** Verifies retrieval diagnostics exercise the same official-document boundary as chat prompt context. */
class ChatControllerRetrievalDiagnosticsTest {

    private static final String EXACT_OVERLOAD_QUERY = "Compare Java 21 and Java 24 for java.util.List.of(E, E).";
    private static final String JAVA_21_LIST_URL =
            "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/List.html";
    private static final String EXACT_OVERLOAD_ANCHOR = "of(E,E)";

    @Test
    void retrievalDiagnosticsUsesOfficialDocumentationPromptContext() {
        ChatService chatService = mock(ChatService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        Document officialPromptDocument = mock(Document.class);
        when(chatService.retrieveTokenConstrainedOfficialDocumentation(EXACT_OVERLOAD_QUERY))
                .thenReturn(new RetrievalService.RetrievalOutcome(List.of(officialPromptDocument), List.of()));
        Citation exactOverloadCitation =
                new Citation(JAVA_21_LIST_URL, "List (Java SE 21 & JDK 21)", EXACT_OVERLOAD_ANCHOR, "Exact overload");
        when(retrievalService.toCitationsForQuery(EXACT_OVERLOAD_QUERY, List.of(officialPromptDocument)))
                .thenReturn(new RetrievalService.CitationOutcome(List.of(exactOverloadCitation), 0));
        ChatController chatController = new ChatController(
                chatService,
                mock(ChatMemoryService.class),
                mock(OpenAIStreamingService.class),
                retrievalService,
                mock(SseSupport.class),
                new ExceptionResponseBuilder(),
                new AppProperties());

        RetrievalDiagnosticsResponse diagnostics = chatController.retrievalDiagnostics(EXACT_OVERLOAD_QUERY);

        verify(chatService).retrieveTokenConstrainedOfficialDocumentation(EXACT_OVERLOAD_QUERY);
        verify(retrievalService).toCitationsForQuery(EXACT_OVERLOAD_QUERY, List.of(officialPromptDocument));
        verify(retrievalService, never()).toCitations(anyList());
        assertEquals(1, diagnostics.docs().size());
        assertEquals(JAVA_21_LIST_URL, diagnostics.docs().getFirst().getUrl());
        assertEquals(EXACT_OVERLOAD_ANCHOR, diagnostics.docs().getFirst().getAnchor());
        assertNull(diagnostics.error());
    }
}
