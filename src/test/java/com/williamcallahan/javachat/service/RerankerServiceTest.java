package com.williamcallahan.javachat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ensures reranker surfaces failures instead of silently falling back.
 */
class RerankerServiceTest {

    @Test
    void rerankThrowsWhenServiceUnavailable() {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        when(streamingService.isAvailable()).thenReturn(false);

        RerankerService rerankerService = new RerankerService(streamingService, new ObjectMapper());
        List<Document> docs = List.of(new Document("first"), new Document("second"));

        assertThrows(RerankingFailureException.class, () -> rerankerService.rerank("query", docs, 2));
    }
}
