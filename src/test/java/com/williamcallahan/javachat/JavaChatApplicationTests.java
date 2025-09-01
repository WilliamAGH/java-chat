package com.williamcallahan.javachat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test",
        "spring.ai.vectorstore.qdrant.host=localhost",
        "spring.ai.vectorstore.qdrant.use-tls=false",
        "spring.ai.vectorstore.qdrant.port=8086",
        "spring.ai.vectorstore.qdrant.initialize-schema=false"
})
class JavaChatApplicationTests {

    @Test
    void contextLoads() {
    }

}
