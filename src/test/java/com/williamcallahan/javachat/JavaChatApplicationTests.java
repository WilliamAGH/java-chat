package com.williamcallahan.javachat;

import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Verifies the Spring Boot test context loads with mocked AI dependencies.
 */
@SpringBootTest(
        properties = {
            "spring.ai.openai.api-key=test",
            "spring.ai.openai.chat.api-key=test",
            "spring.ai.vectorstore.qdrant.host=localhost",
            "spring.ai.vectorstore.qdrant.use-tls=false",
            "spring.ai.vectorstore.qdrant.port=8086"
        })
class JavaChatApplicationTests {

    @MockitoBean
    ChatModel chatModel;

    @MockitoBean
    EmbeddingModel embeddingModel;

    @MockitoBean
    QdrantClient qdrantClient;

    @Test
    void contextLoads() {}
}
