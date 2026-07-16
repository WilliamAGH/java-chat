package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.model.Enrichment;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** Verifies enrichment responses honor the strict structured-output boundary. */
class EnrichmentServiceTest {
    private static final String ENRICHMENT_USER_QUERY = "How does List.copyOf work?";
    private static final String ENRICHMENT_JDK_VERSION = "25";
    private static final List<String> ENRICHMENT_CONTEXT_SNIPPETS =
            List.of("List.copyOf returns an unmodifiable list.");

    private OpenAIStreamingService openAIStreamingService;
    private EnrichmentService enrichmentService;

    @BeforeEach
    void setUp() {
        openAIStreamingService = mock(OpenAIStreamingService.class);
        when(openAIStreamingService.isAvailable()).thenReturn(true);
        enrichmentService = new EnrichmentService(new ObjectMapper(), openAIStreamingService);
    }

    @Test
    void enrichUsesJsonObjectCompletionBoundary() {
        when(openAIStreamingService.completeJsonObject(anyString(), anyDouble(), anyInt()))
                .thenReturn(Mono.just("{\"hints\":[\"Use List.copyOf for an unmodifiable snapshot.\"]}"));

        Enrichment enrichment =
                enrichmentService.enrich(ENRICHMENT_USER_QUERY, ENRICHMENT_JDK_VERSION, ENRICHMENT_CONTEXT_SNIPPETS);

        assertEquals(ENRICHMENT_JDK_VERSION, enrichment.getJdkVersion());
        assertEquals(List.of("Use List.copyOf for an unmodifiable snapshot."), enrichment.getHints());
        verify(openAIStreamingService).completeJsonObject(anyString(), anyDouble(), anyInt());
        verify(openAIStreamingService, never()).complete(anyString(), anyDouble());
    }

    @Test
    void enrichRejectsUnexpectedPreambleWithBraces() {
        when(openAIStreamingService.completeJsonObject(anyString(), anyDouble(), anyInt()))
                .thenReturn(Mono.just("Here is the JSON (see {docs} for details):\n{\"hints\":[\"tip\"]}"));

        IllegalStateException jsonContractFailure = assertThrows(
                IllegalStateException.class,
                () -> enrichmentService.enrich(
                        ENRICHMENT_USER_QUERY, ENRICHMENT_JDK_VERSION, ENRICHMENT_CONTEXT_SNIPPETS));

        assertTrue(jsonContractFailure.getMessage().startsWith("LLM enrichment response was not valid JSON:"));
        assertInstanceOf(JsonProcessingException.class, jsonContractFailure.getCause());
    }

    @Test
    void enrichRejectsMalformedJsonObject() {
        when(openAIStreamingService.completeJsonObject(anyString(), anyDouble(), anyInt()))
                .thenReturn(Mono.just("{\"hints\":["));

        IllegalStateException jsonContractFailure = assertThrows(
                IllegalStateException.class,
                () -> enrichmentService.enrich(
                        ENRICHMENT_USER_QUERY, ENRICHMENT_JDK_VERSION, ENRICHMENT_CONTEXT_SNIPPETS));

        assertTrue(jsonContractFailure.getMessage().startsWith("LLM enrichment response was not valid JSON:"));
        assertInstanceOf(JsonProcessingException.class, jsonContractFailure.getCause());
    }

    @Test
    void enrichRejectsUnknownResponseFields() {
        when(openAIStreamingService.completeJsonObject(anyString(), anyDouble(), anyInt()))
                .thenReturn(Mono.just("{\"hint\":[\"Use the documented API.\"]}"));

        IllegalStateException jsonContractFailure = assertThrows(
                IllegalStateException.class,
                () -> enrichmentService.enrich(
                        ENRICHMENT_USER_QUERY, ENRICHMENT_JDK_VERSION, ENRICHMENT_CONTEXT_SNIPPETS));

        assertTrue(jsonContractFailure.getMessage().startsWith("LLM enrichment response was not valid JSON:"));
        assertInstanceOf(JsonProcessingException.class, jsonContractFailure.getCause());
    }

    @Test
    void enrichRejectsTrailingTokensAndScalarCoercion() {
        List<String> invalidEnrichmentJsonValues = List.of(
                "{\"hints\":[\"tip\"]} trailing",
                "{\"hints\":[\"tip\"]}{\"hints\":[\"second\"]}",
                "{\"hints\":[1,true]}",
                "{\"jdkVersion\":25}");

        for (String invalidEnrichmentJson : invalidEnrichmentJsonValues) {
            when(openAIStreamingService.completeJsonObject(anyString(), anyDouble(), anyInt()))
                    .thenReturn(Mono.just(invalidEnrichmentJson));

            IllegalStateException jsonContractFailure = assertThrows(
                    IllegalStateException.class,
                    () -> enrichmentService.enrich(
                            ENRICHMENT_USER_QUERY, ENRICHMENT_JDK_VERSION, ENRICHMENT_CONTEXT_SNIPPETS));

            assertTrue(jsonContractFailure.getMessage().startsWith("LLM enrichment response was not valid JSON:"));
            assertInstanceOf(JsonProcessingException.class, jsonContractFailure.getCause());
        }
    }
}
