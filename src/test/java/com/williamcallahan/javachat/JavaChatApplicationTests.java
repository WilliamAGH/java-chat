package com.williamcallahan.javachat;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.williamcallahan.javachat.config.QdrantHealthIndicator;
import com.williamcallahan.javachat.service.EmbeddingClient;
import com.williamcallahan.javachat.service.EmbeddingModelKeepAlive;
import com.williamcallahan.javachat.service.EmbeddingServiceUnavailableException;
import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the Spring Boot test context loads with mocked AI dependencies.
 */
@SpringBootTest(
        properties = {
            "management.endpoint.health.probes.enabled=true",
            "management.endpoint.health.group.readiness.include=readinessState",
            "management.endpoint.health.group.dependencies.include=qdrant,embeddingModelKeepAlive",
            "spring.ai.vectorstore.qdrant.host=localhost",
            "spring.ai.vectorstore.qdrant.use-tls=false",
            "spring.ai.vectorstore.qdrant.port=8086"
        })
@AutoConfigureMockMvc
@AutoConfigureObservability
class JavaChatApplicationTests {

    private static final String TEST_SOURCE_COMMIT = "java-chat-test-source-commit";

    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void configureDeploymentIdentity(DynamicPropertyRegistry propertyRegistry) {
        propertyRegistry.add("SOURCE_COMMIT", () -> TEST_SOURCE_COMMIT);
    }

    @MockitoBean(answers = Answers.RETURNS_MOCKS)
    EmbeddingClient embeddingClient;

    @MockitoBean
    QdrantClient qdrantClient;

    @Autowired
    EmbeddingModelKeepAlive embeddingModelKeepAlive;

    @Autowired
    QdrantHealthIndicator qdrantHealthIndicator;

    @BeforeEach
    void reportExternalDependenciesUnavailable() {
        doThrow(new EmbeddingServiceUnavailableException("provider unavailable for readiness test"))
                .when(embeddingClient)
                .warmUp();
    }

    @Test
    void contextLoads() {}

    @Test
    void exposesOnlyOperationalActuatorSurfaces() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_")));
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deployment.commit").value(TEST_SOURCE_COMMIT))
                .andExpect(jsonPath("$.build.commit").isString());
    }

    @Test
    void externalDependencyFailuresRemainObservableWithoutBlockingReadiness() throws Exception {
        assertEquals(Status.DOWN, embeddingModelKeepAlive.health().getStatus());
        assertEquals(Status.DOWN, qdrantHealthIndicator.health().getStatus());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/dependencies")).andExpect(status().isServiceUnavailable());
    }
}
