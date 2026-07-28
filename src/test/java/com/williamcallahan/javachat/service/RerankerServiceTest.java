package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Mono;

/** Verifies reranker ordering and failure behavior. */
class RerankerServiceTest {
    private static final Duration TEST_RERANKER_TIMEOUT = Duration.ofSeconds(45);
    private static final double TEST_RERANKER_TEMPERATURE = 0.2;
    private static final int TEST_RERANKER_OUTPUT_TOKEN_BUDGET = 384;

    @Test
    void rerankPreservesAtomicProviderAdmissionFailure() {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        ConfiguredProviderTemporarilyUnavailableException admissionFailure =
                new ConfiguredProviderTemporarilyUnavailableException(RateLimitService.ApiProvider.OPENAI);
        when(streamingService.completeJsonObject(
                        anyString(),
                        eq(TEST_RERANKER_TEMPERATURE),
                        eq(TEST_RERANKER_OUTPUT_TOKEN_BUDGET),
                        eq(TEST_RERANKER_TIMEOUT)))
                .thenReturn(Mono.error(admissionFailure));

        RerankerService rerankerService =
                new RerankerService(streamingService, new ObjectMapper(), configuredRerankerProperties());
        List<Document> sourceDocuments = List.of(new Document("first"), new Document("second"));

        RerankingFailureException rerankingFailure = assertThrows(
                RerankingFailureException.class, () -> rerankerService.rerank("query", sourceDocuments, 2));

        assertEquals(admissionFailure, rerankingFailure.getCause());
    }

    @Test
    void rerankUsesConfiguredCompletionBudgetAndTimeout() {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        when(streamingService.completeJsonObject(
                        anyString(),
                        eq(TEST_RERANKER_TEMPERATURE),
                        eq(TEST_RERANKER_OUTPUT_TOKEN_BUDGET),
                        eq(TEST_RERANKER_TIMEOUT)))
                .thenReturn(Mono.just("{\"order\":[1,0]}"));

        RerankerService rerankerService =
                new RerankerService(streamingService, new ObjectMapper(), configuredRerankerProperties());
        List<Document> sourceDocuments = List.of(new Document("first"), new Document("second"));

        List<Document> rankedDocuments = rerankerService.rerank("query", sourceDocuments, 2);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> outputBudgetCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(streamingService)
                .completeJsonObject(
                        promptCaptor.capture(),
                        eq(TEST_RERANKER_TEMPERATURE),
                        outputBudgetCaptor.capture(),
                        eq(TEST_RERANKER_TIMEOUT));
        verify(streamingService, never()).complete(anyString(), eq(TEST_RERANKER_TEMPERATURE));
        assertTrue(promptCaptor.getValue().contains("Valid indices are 0 through 1."));
        assertTrue(promptCaptor.getValue().contains("Include each relevant index at most once"));
        assertTrue(promptCaptor.getValue().contains("Return {\"order\":[]} when no document is relevant"));
        assertEquals(TEST_RERANKER_OUTPUT_TOKEN_BUDGET, outputBudgetCaptor.getValue());
        assertEquals(List.of(sourceDocuments.get(1), sourceDocuments.get(0)), rankedDocuments);
    }

    @Test
    void rerankSelectsRelevantSubsetAndDropsUnrelatedDocuments() {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        when(streamingService.completeJsonObject(
                        anyString(),
                        eq(TEST_RERANKER_TEMPERATURE),
                        eq(TEST_RERANKER_OUTPUT_TOKEN_BUDGET),
                        eq(TEST_RERANKER_TIMEOUT)))
                .thenReturn(Mono.just("{\"order\":[2,0]}"));

        RerankerService rerankerService =
                new RerankerService(streamingService, new ObjectMapper(), configuredRerankerProperties());
        List<Document> sourceDocuments = List.of(new Document("first"), new Document("second"), new Document("third"));

        List<Document> selectedDocuments = rerankerService.rerank("query", sourceDocuments, 5);

        assertEquals(List.of(sourceDocuments.get(2), sourceDocuments.get(0)), selectedDocuments);
    }

    @Test
    void rerankReturnsEmptySelectionWhenNoDocumentIsRelevant() {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        when(streamingService.completeJsonObject(
                        anyString(),
                        eq(TEST_RERANKER_TEMPERATURE),
                        eq(TEST_RERANKER_OUTPUT_TOKEN_BUDGET),
                        eq(TEST_RERANKER_TIMEOUT)))
                .thenReturn(Mono.just("{\"order\":[]}"));

        RerankerService rerankerService =
                new RerankerService(streamingService, new ObjectMapper(), configuredRerankerProperties());
        List<Document> sourceDocuments = List.of(new Document("first"), new Document("second"));

        List<Document> selectedDocuments = rerankerService.rerank("query", sourceDocuments, 5);

        assertTrue(selectedDocuments.isEmpty());
    }

    @Test
    void rerankRejectsDuplicateInvalidAndMalformedOrderings() {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        RerankerService rerankerService =
                new RerankerService(streamingService, new ObjectMapper(), configuredRerankerProperties());
        List<Document> sourceDocuments =
                List.of(new Document("first"), new Document("second"), new Document("third"), new Document("fourth"));

        List<String> invalidOrderingJsonValues = List.of(
                "{\"order\":[1,1,0,2]}",
                "{\"order\":[null,-1,99,2]}",
                "{\"order\":[0,1,2,3],\"explanation\":\"extra\"}",
                "Here is the order: {\"order\":[0,1,2,3]}",
                "```json\n{\"order\":[0,1,2,3]}\n```",
                "{\"order\":[0,1,2,3]} trailing",
                "{\"order\":[0,1,2,3]}{\"order\":[3,2,1,0]}",
                "{\"order\":[0,1,2,3],\"order\":[3,2,1,0]}",
                "{\"order\":[0.9,1.1,2.2,3.3]}",
                "{\"order\":[\"0\",\"1\",\"2\",\"3\"]}");
        for (String invalidOrderingJson : invalidOrderingJsonValues) {
            when(streamingService.completeJsonObject(
                            anyString(),
                            eq(TEST_RERANKER_TEMPERATURE),
                            eq(TEST_RERANKER_OUTPUT_TOKEN_BUDGET),
                            eq(TEST_RERANKER_TIMEOUT)))
                    .thenReturn(Mono.just(invalidOrderingJson));

            assertThrows(RerankingFailureException.class, () -> rerankerService.rerank("query", sourceDocuments, 4));
        }
    }

    @Test
    void emptyCompletionIsTerminalWithoutCallingAnotherCompletionPath() {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        when(streamingService.completeJsonObject(
                        anyString(),
                        eq(TEST_RERANKER_TEMPERATURE),
                        eq(TEST_RERANKER_OUTPUT_TOKEN_BUDGET),
                        eq(TEST_RERANKER_TIMEOUT)))
                .thenReturn(Mono.empty());
        RerankerService rerankerService =
                new RerankerService(streamingService, new ObjectMapper(), configuredRerankerProperties());
        List<Document> sourceDocuments = List.of(new Document("first"), new Document("second"));

        RerankingFailureException rerankingFailure = assertThrows(
                RerankingFailureException.class, () -> rerankerService.rerank("query", sourceDocuments, 2));

        assertEquals("Reranking response was empty", rerankingFailure.getMessage());
        verify(streamingService, never()).complete(anyString(), eq(TEST_RERANKER_TEMPERATURE));
    }

    @Test
    void cacheIdentitySeparatesUrlAndTextBoundaries() {
        List<Document> firstDocuments = List.of(new Document("c", Map.of(QdrantPayloadFieldSchema.URL_FIELD, "ab")));
        List<Document> secondDocuments = List.of(new Document("bc", Map.of(QdrantPayloadFieldSchema.URL_FIELD, "a")));

        assertNotEquals(
                RerankerService.computeDocsHash(firstDocuments), RerankerService.computeDocsHash(secondDocuments));
    }

    @Test
    void cacheIdentityChangesWhenCitationMetadataChanges() {
        Document originalDocument = new Document(
                "document-id",
                "same text",
                Map.of(
                        QdrantPayloadFieldSchema.URL_FIELD,
                        "https://docs.example/java",
                        QdrantPayloadFieldSchema.TITLE_FIELD,
                        "Original title"));
        Document refreshedDocument = new Document(
                "document-id",
                "same text",
                Map.of(
                        QdrantPayloadFieldSchema.URL_FIELD,
                        "https://docs.example/java",
                        QdrantPayloadFieldSchema.TITLE_FIELD,
                        "Refreshed title"));

        assertNotEquals(
                RerankerService.computeDocsHash(List.of(originalDocument)),
                RerankerService.computeDocsHash(List.of(refreshedDocument)));
    }

    private static AppProperties configuredRerankerProperties() {
        AppProperties appProperties = new AppProperties();
        appProperties.getRag().setRerankerTimeout(TEST_RERANKER_TIMEOUT);
        appProperties.getLlm().setRerankerTemperature(TEST_RERANKER_TEMPERATURE);
        appProperties.getLlm().setRerankerOutputTokenBudget(TEST_RERANKER_OUTPUT_TOKEN_BUDGET);
        return appProperties;
    }
}
