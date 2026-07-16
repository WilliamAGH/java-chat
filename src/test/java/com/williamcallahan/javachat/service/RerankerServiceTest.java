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

/** Verifies reranker ordering and failure behavior. */
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
        assertEquals(List.of(sourceDocuments.get(1), sourceDocuments.get(0)), rankedDocuments);
    }

    @Test
    void rerankRejectsIncompleteDuplicateAndInvalidOrderings() {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        when(streamingService.isAvailable()).thenReturn(true);
        Duration rerankerTimeout = Duration.ofSeconds(45);
        AppProperties appProperties = new AppProperties();
        appProperties.getRag().setRerankerTimeout(rerankerTimeout);
        RerankerService rerankerService = new RerankerService(streamingService, new ObjectMapper(), appProperties);
        List<Document> sourceDocuments =
                List.of(new Document("first"), new Document("second"), new Document("third"), new Document("fourth"));

        List<String> invalidOrderingJsonValues = List.of(
                "{\"order\":[2]}",
                "{\"order\":[1,1,0,2]}",
                "{\"order\":[null,-1,99,2]}",
                "{\"order\":[0,1,2,3],\"explanation\":\"extra\"}",
                "Here is the order: {\"order\":[0,1,2,3]}",
                "```json\n{\"order\":[0,1,2,3]}\n```",
                "{\"order\":[0,1,2,3]} trailing",
                "{\"order\":[0,1,2,3]}{\"order\":[3,2,1,0]}",
                "{\"order\":[0.9,1.1,2.2,3.3]}",
                "{\"order\":[\"0\",\"1\",\"2\",\"3\"]}");
        for (String invalidOrderingJson : invalidOrderingJsonValues) {
            when(streamingService.completeJsonObject(anyString(), eq(0.0), anyInt(), eq(rerankerTimeout)))
                    .thenReturn(Mono.just(invalidOrderingJson));

            assertThrows(RerankingFailureException.class, () -> rerankerService.rerank("query", sourceDocuments, 4));
        }
    }
}
