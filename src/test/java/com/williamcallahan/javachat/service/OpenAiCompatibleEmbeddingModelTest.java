package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.core.RequestOptions;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.services.blocking.EmbeddingService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Verifies OpenAI embedding responses preserve request ordering.
 */
class OpenAiCompatibleEmbeddingModelTest {

    @Test
    void callUsesSdkAndPreservesIndexOrdering() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);

        when(client.embeddings()).thenReturn(embeddingService);

        CreateEmbeddingResponse response = CreateEmbeddingResponse.builder()
                .model("text-embedding-3-small")
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

        try (OpenAiCompatibleEmbeddingModel model =
                OpenAiCompatibleEmbeddingModel.create(client, "text-embedding-3-small", 2)) {
            EmbeddingResponse embeddingResponse = model.call(new EmbeddingRequest(List.of("a", "b"), null));

            assertEquals(2, embeddingResponse.getResults().size());
            assertEquals(0.25f, embeddingResponse.getResults().get(0).getOutput()[0]);
            assertEquals(-0.5f, embeddingResponse.getResults().get(0).getOutput()[1]);
            assertEquals(0.0f, embeddingResponse.getResults().get(1).getOutput()[0]);
            assertEquals(1.0f, embeddingResponse.getResults().get(1).getOutput()[1]);

            verify(embeddingService).create(any(), any(RequestOptions.class));
        }
    }
}
