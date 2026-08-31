package com.williamcallahan.javachat.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.model.KnowledgeGroup;
import com.williamcallahan.javachat.service.KnowledgeBaseInventoryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the knowledge-base endpoint serializes the inventory the service returns.
 */
@WebMvcTest(controllers = KnowledgeBaseController.class)
@Import({AppProperties.class, ExceptionResponseBuilder.class})
@WithMockUser
class KnowledgeBaseControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    KnowledgeBaseInventoryService knowledgeBaseInventoryService;

    @Test
    void listsIngestedKnowledgeGroups() throws Exception {
        when(knowledgeBaseInventoryService.listKnowledgeGroups())
                .thenReturn(List.of(
                        new KnowledgeGroup("chat-docs", "DOCS", "oracle/javase/25/api", 10),
                        new KnowledgeGroup("chat-github-repo", "GITHUB", "https://github.com/acme/repo", 7)));

        mockMvc.perform(get("/api/knowledge/groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].collection").value("chat-docs"))
                .andExpect(jsonPath("$[0].kind").value("DOCS"))
                .andExpect(jsonPath("$[0].name").value("oracle/javase/25/api"))
                .andExpect(jsonPath("$[0].chunks").value(10))
                .andExpect(jsonPath("$[1].collection").value("chat-github-repo"))
                .andExpect(jsonPath("$[1].kind").value("GITHUB"))
                .andExpect(jsonPath("$[1].chunks").value(7));
    }

    @Test
    void serializesAnEmptyInventory() throws Exception {
        when(knowledgeBaseInventoryService.listKnowledgeGroups()).thenReturn(List.of());

        mockMvc.perform(get("/api/knowledge/groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
