package com.williamcallahan.javachat.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiCompatibleEmbeddingModelTest {

    @Test
    void callBatchesInputsAndParsesTypedResponse() {
        RestTemplate restTemplate = new RestTemplateBuilder().build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

        String responseJson = """
            {
              "data": [
                { "index": 0, "embedding": [0.25, -0.5] },
                { "index": 1, "embedding": [0.0, 1.0] }
              ]
            }
            """;

        server.expect(requestTo("https://api.openai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer test-key"))
            .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        OpenAiCompatibleEmbeddingModel model = new OpenAiCompatibleEmbeddingModel(
            "https://api.openai.com",
            "test-key",
            "text-embedding-3-small",
            2,
            restTemplate
        );

        EmbeddingResponse response = model.call(new EmbeddingRequest(List.of("a", "b"), null));

        assertEquals(2, response.getResults().size());
        assertEquals(0.25f, response.getResults().get(0).getOutput()[0]);
        assertEquals(-0.5f, response.getResults().get(0).getOutput()[1]);
        assertEquals(0.0f, response.getResults().get(1).getOutput()[0]);
        assertEquals(1.0f, response.getResults().get(1).getOutput()[1]);

        server.verify();
    }
}

