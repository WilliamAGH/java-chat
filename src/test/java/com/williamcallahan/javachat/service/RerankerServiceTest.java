package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Mono;

/** Verifies reranker ordering and failure behavior. */
class RerankerServiceTest {
    private static final Duration TEST_RERANKER_TIMEOUT = Duration.ofSeconds(45);
    private static final double TEST_RERANKER_TEMPERATURE = 0.2;
    private static final int TEST_RERANKER_OUTPUT_TOKEN_BUDGET = 384;
    private static final Logger RERANKER_LOGGER = (Logger) LoggerFactory.getLogger(RerankerService.class);

    @Test
    void rerankThrowsWhenServiceUnavailable() {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        when(streamingService.isAvailable()).thenReturn(false);

        RerankerService rerankerService =
                new RerankerService(streamingService, new ObjectMapper(), configuredRerankerProperties());
        List<Document> sourceDocuments = List.of(new Document("first"), new Document("second"));

        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(RERANKER_LOGGER)) {
            assertThrows(RerankingFailureException.class, () -> rerankerService.rerank("query", sourceDocuments, 2));

            assertEquals(1, expectedLogEvents.events().size());
            var unavailableWarning = expectedLogEvents.events().getFirst();
            assertEquals(Level.WARN, unavailableWarning.getLevel());
            assertEquals(
                    "OpenAIStreamingService unavailable; skipping LLM rerank",
                    unavailableWarning.getFormattedMessage());
            assertNull(unavailableWarning.getThrowableProxy());
        }
    }

    @Test
    void rerankUsesConfiguredCompletionBudgetAndTimeout() {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        when(streamingService.isAvailable()).thenReturn(true);
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
        assertTrue(promptCaptor.getValue().contains("Include each valid index exactly once"));
        assertEquals(TEST_RERANKER_OUTPUT_TOKEN_BUDGET, outputBudgetCaptor.getValue());
        assertEquals(List.of(sourceDocuments.get(1), sourceDocuments.get(0)), rankedDocuments);
    }

    @Test
    void rerankRejectsIncompleteDuplicateAndInvalidOrderings() {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        when(streamingService.isAvailable()).thenReturn(true);
        RerankerService rerankerService =
                new RerankerService(streamingService, new ObjectMapper(), configuredRerankerProperties());
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

    private static AppProperties configuredRerankerProperties() {
        AppProperties appProperties = new AppProperties();
        appProperties.getRag().setRerankerTimeout(TEST_RERANKER_TIMEOUT);
        appProperties.getLlm().setRerankerTemperature(TEST_RERANKER_TEMPERATURE);
        appProperties.getLlm().setRerankerOutputTokenBudget(TEST_RERANKER_OUTPUT_TOKEN_BUDGET);
        return appProperties;
    }
}
