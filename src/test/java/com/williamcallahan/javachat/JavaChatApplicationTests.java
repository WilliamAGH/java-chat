package com.williamcallahan.javachat;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.williamcallahan.javachat.config.QdrantHealthIndicator;
import com.williamcallahan.javachat.service.EmbeddingClient;
import com.williamcallahan.javachat.service.EmbeddingModelKeepAlive;
import com.williamcallahan.javachat.service.EmbeddingServiceUnavailableException;
import com.williamcallahan.javachat.service.ExternalServiceHealth;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import io.qdrant.client.QdrantClient;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;
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
            "management.endpoint.health.group.readiness.include=readinessState,qdrant",
            "management.endpoint.health.group.dependencies.include=qdrant,embeddingModelKeepAlive",
            "spring.ai.vectorstore.qdrant.host=localhost",
            "spring.ai.vectorstore.qdrant.use-tls=false"
        })
@AutoConfigureMockMvc
@AutoConfigureObservability
@ContextConfiguration(initializers = JavaChatApplicationTests.ExternalServiceHealthLogCaptureInitializer.class)
class JavaChatApplicationTests {

    private static final String TEST_SOURCE_COMMIT = "java-chat-test-source-commit";
    private static final int UNAVAILABLE_QDRANT_TEST_PORT = 1;
    private static final Logger EXTERNAL_SERVICE_HEALTH_LOGGER =
            (Logger) LoggerFactory.getLogger(ExternalServiceHealth.class);
    private static final AtomicReference<ExpectedLogEvents> EXTERNAL_SERVICE_HEALTH_LOG_EVENTS =
            new AtomicReference<>();

    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void configureDeploymentIdentity(DynamicPropertyRegistry propertyRegistry) {
        propertyRegistry.add("SOURCE_COMMIT", () -> TEST_SOURCE_COMMIT);
        propertyRegistry.add("spring.ai.vectorstore.qdrant.port", () -> UNAVAILABLE_QDRANT_TEST_PORT);
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
    void qdrantFailureBlocksReadinessAndAllDependencyFailuresRemainObservable() throws Exception {
        assertEquals(Status.DOWN, embeddingModelKeepAlive.health().getStatus());
        assertEquals(Status.DOWN, qdrantHealthIndicator.health().getStatus());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/actuator/health/dependencies")).andExpect(status().isServiceUnavailable());
    }

    @AfterAll
    static void assertsQdrantStartupWarningsAndRestoresLogger() {
        ExpectedLogEvents externalServiceHealthLogEvents = EXTERNAL_SERVICE_HEALTH_LOG_EVENTS.getAndSet(null);
        assertNotNull(externalServiceHealthLogEvents);
        try (externalServiceHealthLogEvents) {
            assertEquals(
                    2L,
                    externalServiceHealthLogEvents.events().stream()
                            .filter(logEvent -> logEvent.getLevel() == Level.WARN)
                            .count());
            assertQdrantHealthWarning(externalServiceHealthLogEvents, "[HEALTH] Qdrant connectivity check failed");
            assertQdrantHealthWarning(externalServiceHealthLogEvents, "[HEALTH] Qdrant health check failed");
        }
    }

    private static void assertQdrantHealthWarning(
            ExpectedLogEvents externalServiceHealthLogEvents, String messagePrefix) {
        var qdrantHealthWarning = externalServiceHealthLogEvents.events().stream()
                .filter(logEvent -> logEvent.getLevel() == Level.WARN)
                .filter(logEvent -> logEvent.getFormattedMessage().startsWith(messagePrefix))
                .findFirst()
                .orElseThrow();
        assertEquals(Level.WARN, qdrantHealthWarning.getLevel());
        assertTrue(qdrantHealthWarning.getFormattedMessage().contains("Will retry in"));
        assertNull(qdrantHealthWarning.getThrowableProxy());
    }

    /** Starts log capture before the Spring context schedules its first dependency probe. */
    static final class ExternalServiceHealthLogCaptureInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            EXTERNAL_SERVICE_HEALTH_LOG_EVENTS.set(ExpectedLogEvents.capture(EXTERNAL_SERVICE_HEALTH_LOGGER));
        }
    }
}
