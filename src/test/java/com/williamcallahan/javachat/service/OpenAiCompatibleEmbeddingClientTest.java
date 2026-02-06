package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.core.RequestOptions;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.services.blocking.EmbeddingService;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies OpenAI embedding responses preserve request ordering.
 */
class OpenAiCompatibleEmbeddingClientTest {

    @Test
    void callUsesSdkAndPreservesIndexOrdering() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);

        when(client.embeddings()).thenReturn(embeddingService);

        CreateEmbeddingResponse response = CreateEmbeddingResponse.builder()
                .model("text-embedding-qwen3-embedding-8b")
                .usage(CreateEmbeddingResponse.Usage.builder()
                        .promptTokens(1L)
                        .totalTokens(1L)
                        .build())
                .data(List.of(
                        com.openai.models.embeddings.Embedding.builder()
                                .index(1L)
                                .embedding(List.of(0.0f, 1.0f))
                                .build(),
                        com.openai.models.embeddings.Embedding.builder()
                                .index(0L)
                                .embedding(List.of(0.25f, -0.5f))
                                .build()))
                .build();

        when(embeddingService.create(any(), any(RequestOptions.class))).thenReturn(response);

        try (OpenAiCompatibleEmbeddingClient clientAdapter =
                OpenAiCompatibleEmbeddingClient.create(client, "text-embedding-qwen3-embedding-8b", 2)) {
            List<float[]> vectors = clientAdapter.embed(List.of("a", "b"));

            assertEquals(2, vectors.size());
            assertEquals(0.25f, vectors.get(0)[0]);
            assertEquals(-0.5f, vectors.get(0)[1]);
            assertEquals(0.0f, vectors.get(1)[0]);
            assertEquals(1.0f, vectors.get(1)[1]);

            verify(embeddingService).create(any(), any(RequestOptions.class));
        }
    }

    @Test
    void throwsWhenEmbeddingDimensionDoesNotMatchConfiguration() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);

        when(client.embeddings()).thenReturn(embeddingService);

        CreateEmbeddingResponse response = CreateEmbeddingResponse.builder()
                .model("text-embedding-qwen3-embedding-8b")
                .usage(CreateEmbeddingResponse.Usage.builder()
                        .promptTokens(1L)
                        .totalTokens(1L)
                        .build())
                .data(List.of(com.openai.models.embeddings.Embedding.builder()
                        .index(0L)
                        .embedding(List.of(0.1f, 0.2f, 0.3f))
                        .build()))
                .build();

        when(embeddingService.create(any(), any(RequestOptions.class))).thenReturn(response);

        try (OpenAiCompatibleEmbeddingClient clientAdapter =
                OpenAiCompatibleEmbeddingClient.create(client, "text-embedding-qwen3-embedding-8b", 2)) {
            EmbeddingServiceUnavailableException thrownException =
                    assertThrows(EmbeddingServiceUnavailableException.class, () -> clientAdapter.embed(List.of("a")));
            assertTrue(thrownException.getMessage().contains("dimension mismatch"));
            verify(embeddingService, times(1)).create(any(), any(RequestOptions.class));
        }
    }

    @Test
    void retriesTransientResponseValidationFailuresAndRecovers() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);

        when(client.embeddings()).thenReturn(embeddingService);

        CreateEmbeddingResponse malformedResponse = CreateEmbeddingResponse.builder()
                .model("text-embedding-qwen3-embedding-8b")
                .usage(CreateEmbeddingResponse.Usage.builder()
                        .promptTokens(1L)
                        .totalTokens(1L)
                        .build())
                .data(List.of(com.openai.models.embeddings.Embedding.builder()
                        .index(10L)
                        .embedding(List.of(0.5f, 0.6f))
                        .build()))
                .build();

        CreateEmbeddingResponse recoveredResponse = CreateEmbeddingResponse.builder()
                .model("text-embedding-qwen3-embedding-8b")
                .usage(CreateEmbeddingResponse.Usage.builder()
                        .promptTokens(1L)
                        .totalTokens(1L)
                        .build())
                .data(List.of(com.openai.models.embeddings.Embedding.builder()
                        .index(0L)
                        .embedding(List.of(0.7f, 0.8f))
                        .build()))
                .build();

        when(embeddingService.create(any(), any(RequestOptions.class)))
                .thenReturn(malformedResponse, recoveredResponse);

        try (OpenAiCompatibleEmbeddingClient clientAdapter =
                OpenAiCompatibleEmbeddingClient.create(client, "text-embedding-qwen3-embedding-8b", 2)) {
            List<float[]> vectors = clientAdapter.embed(List.of("single"));

            assertEquals(1, vectors.size());
            assertEquals(0.7f, vectors.get(0)[0]);
            assertEquals(0.8f, vectors.get(0)[1]);
            verify(embeddingService, times(2)).create(any(), any(RequestOptions.class));
        }
    }
}
