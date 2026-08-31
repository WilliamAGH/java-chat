package com.williamcallahan.javachat.adapters.in.web.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.williamcallahan.javachat.application.knowledge.KnowledgeBaseInventoryUseCase;
import com.williamcallahan.javachat.application.knowledge.KnowledgeInventoryUnavailableException;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.domain.knowledge.KnowledgeGroup;
import com.williamcallahan.javachat.domain.knowledge.KnowledgeInventory;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies the authenticated inventory HTTP contract and gateway failure mapping. */
@WebMvcTest(controllers = KnowledgeBaseController.class)
@Import(AppProperties.class)
@WithMockUser
class KnowledgeBaseControllerTest {
    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    KnowledgeBaseInventoryUseCase knowledgeBaseInventoryUseCase;

    @Test
    void returnsGroupsAndAuthoritativeTotal() throws Exception {
        when(knowledgeBaseInventoryUseCase.listKnowledgeInventory())
                .thenReturn(new KnowledgeInventory(List.of(
                        new KnowledgeGroup("chat-docs", KnowledgeGroup.Kind.DOCS, "oracle/javase/25/api", 10),
                        new KnowledgeGroup(
                                "chat-github-repo", KnowledgeGroup.Kind.GITHUB, "https://github.com/acme/repo", 7))));

        mockMvc.perform(get("/api/knowledge/groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalChunks").value(17))
                .andExpect(jsonPath("$.groups[0].kind").value("DOCS"))
                .andExpect(jsonPath("$.groups[0].chunks").value(10))
                .andExpect(jsonPath("$.groups[1].kind").value("GITHUB"));
    }

    @Test
    void mapsIncompleteInventoryToBadGateway() throws Exception {
        KnowledgeInventoryUnavailableException inventoryFailure =
                new KnowledgeInventoryUnavailableException(new IllegalStateException("Qdrant failed"));
        when(knowledgeBaseInventoryUseCase.listKnowledgeInventory()).thenThrow(inventoryFailure);
        Logger controllerLogger = (Logger) LoggerFactory.getLogger(KnowledgeBaseController.class);

        try (ExpectedLogEvents expectedLogs = ExpectedLogEvents.capture(controllerLogger)) {
            mockMvc.perform(get("/api/knowledge/groups"))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.status").value("error"));

            assertEquals(1, expectedLogs.events().size());
            assertEquals(Level.WARN, expectedLogs.events().getFirst().getLevel());
            assertEquals(
                    "Knowledge inventory unavailable",
                    expectedLogs.events().getFirst().getFormattedMessage());
            assertEquals(
                    KnowledgeInventoryUnavailableException.class.getName(),
                    expectedLogs.events().getFirst().getThrowableProxy().getClassName());
            assertEquals(
                    IllegalStateException.class.getName(),
                    expectedLogs
                            .events()
                            .getFirst()
                            .getThrowableProxy()
                            .getCause()
                            .getClassName());
        }
    }
}
