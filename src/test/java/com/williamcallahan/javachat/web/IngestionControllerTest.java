package com.williamcallahan.javachat.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.service.DocsIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies Spring MVC enforces ingestion request limits before invoking application services.
 */
@ActiveProfiles("test")
@WebMvcTest(controllers = IngestionController.class)
@Import({AppProperties.class, ExceptionResponseBuilder.class})
@WithMockUser
class IngestionControllerTest {
    private static final String DOWNSTREAM_SECRET = "downstream-response-body-secret";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DocsIngestionService docsIngestionService;

    @Test
    void rejectsRemoteIngestionAboveConfiguredPageLimit() throws Exception {
        mockMvc.perform(post("/api/ingest").with(csrf()).param("maxPages", Integer.toString(Integer.MAX_VALUE)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(docsIngestionService);
    }

    @Test
    void rejectsLocalIngestionAboveConfiguredFileLimit() throws Exception {
        mockMvc.perform(post("/api/ingest/local").with(csrf()).param("maxFiles", Integer.toString(Integer.MAX_VALUE)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(docsIngestionService);
    }

    @Test
    void omitsDownstreamDetailsFromRemoteIngestionFailures() throws Exception {
        doThrow(new IllegalStateException(DOWNSTREAM_SECRET))
                .when(docsIngestionService)
                .crawlAndIngest(anyInt());

        mockMvc.perform(post("/api/ingest").with(csrf()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString(DOWNSTREAM_SECRET))));
    }

    @Test
    void omitsFilesystemDetailsFromLocalIngestionFailures() throws Exception {
        when(docsIngestionService.ingestLocalDirectory(anyString(), anyInt()))
                .thenThrow(new IllegalArgumentException(DOWNSTREAM_SECRET));

        mockMvc.perform(post("/api/ingest/local").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString(DOWNSTREAM_SECRET))));
    }
}
