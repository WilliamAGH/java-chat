package com.williamcallahan.javachat;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.williamcallahan.javachat.service.EmbeddingClient;
import com.williamcallahan.javachat.service.EmbeddingModelKeepAlive;
import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the Spring Boot test context loads with mocked AI dependencies.
 */
@SpringBootTest(
        properties = {
            "spring.ai.openai.api-key=test",
            "spring.ai.openai.chat.api-key=test",
            "management.endpoints.web.exposure.include=*",
            "management.endpoint.health.probes.enabled=true",
            "management.endpoint.health.group.readiness.include=readinessState,qdrant,embeddingModelKeepAlive",
            "spring.ai.vectorstore.qdrant.host=localhost",
            "spring.ai.vectorstore.qdrant.use-tls=false",
            "spring.ai.vectorstore.qdrant.port=8086"
        })
@AutoConfigureMockMvc
@AutoConfigureObservability
class JavaChatApplicationTests {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ChatModel chatModel;

    @MockitoBean(answers = Answers.RETURNS_MOCKS)
    EmbeddingClient embeddingClient;

    @MockitoBean
    QdrantClient qdrantClient;

    @MockitoBean
    EmbeddingModelKeepAlive embeddingModelKeepAlive;

    @BeforeEach
    void reportEmbeddingDependencyUnavailable() {
        when(embeddingModelKeepAlive.health()).thenReturn(Health.down().build());
    }

    @Test
    void contextLoads() {}

    @Test
    void exposesOnlyOperationalActuatorSurfaces() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_")));
        mockMvc.perform(get("/actuator/health")).andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/info")).andExpect(status().isForbidden());
    }
}
