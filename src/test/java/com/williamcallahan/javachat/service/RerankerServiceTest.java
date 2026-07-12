package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.config.AppProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Mono;

/**
 * Ensures reranker surfaces failures instead of silently falling back.
 */
class RerankerServiceTest {

    @Test
    void rerankThrowsWhenServiceUnavailable() {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        when(streamingService.isAvailable()).thenReturn(false);

        RerankerService rerankerService =
                new RerankerService(streamingService, new ObjectMapper(), new AppProperties());
        List<Document> sourceDocuments = List.of(new Document("first"), new Document("second"));

        assertThrows(RerankingFailureException.class, () -> rerankerService.rerank("query", sourceDocuments, 2));
    }

    @Test
    void rerankUsesConfiguredCompletionBudgetAndTimeout() {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        when(streamingService.isAvailable()).thenReturn(true);
        Duration rerankerTimeout = Duration.ofSeconds(45);
        when(streamingService.completeJsonObject(anyString(), eq(0.0), anyInt(), eq(rerankerTimeout)))
                .thenReturn(Mono.just("{\"order\":[1,0]}"));

        AppProperties appProperties = new AppProperties();
        appProperties.getRag().setRerankerTimeout(rerankerTimeout);
        RerankerService rerankerService = new RerankerService(streamingService, new ObjectMapper(), appProperties);
        List<Document> sourceDocuments = List.of(new Document("first"), new Document("second"));

        List<Document> rankedDocuments = rerankerService.rerank("query", sourceDocuments, 2);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> outputBudgetCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(streamingService)
                .completeJsonObject(promptCaptor.capture(), eq(0.0), outputBudgetCaptor.capture(), eq(rerankerTimeout));
        verify(streamingService, never()).complete(anyString(), eq(0.0));
        assertTrue(promptCaptor.getValue().contains("Valid indices are 0 through 1."));
        assertTrue(promptCaptor.getValue().contains("Include each valid index exactly once"));
        assertEquals(4_000, outputBudgetCaptor.getValue());
        assertEquals(sourceDocuments.get(1), rankedDocuments.get(0));
    }
}
