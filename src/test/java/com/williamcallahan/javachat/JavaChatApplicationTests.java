package com.williamcallahan.javachat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
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
            "spring.ai.vectorstore.qdrant.port=8086",
            "spring.ai.vectorstore.qdrant.initialize-schema=false"
        })
class JavaChatApplicationTests {

    @MockitoBean
    ChatModel chatModel;

    @Test
    void contextLoads() {}
}
