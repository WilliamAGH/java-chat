package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.model.Enrichment;
import com.williamcallahan.javachat.service.EnrichmentService;
import com.williamcallahan.javachat.service.RetrievalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = EnrichmentController.class)
@org.springframework.test.context.TestPropertySource(properties = "app.docs.jdk-version=24")
@org.springframework.security.test.context.support.WithMockUser
class EnrichmentControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    RetrievalService retrievalService;

    @MockitoBean
    EnrichmentService enrichmentService;

    private static Enrichment withItems(List<String> hints, List<String> reminders, List<String> background) {
        Enrichment e = new Enrichment();
        e.setHints(hints);
        e.setReminders(reminders);
        e.setBackground(background);
        return e;
    }

    @Test
    void enrich_filters_empty_strings_and_whitespace_default_path() throws Exception {
        given(retrievalService.retrieve(any())).willReturn(List.of());
        given(enrichmentService.enrich(any(), any(), any())).willReturn(
                withItems(List.of("  ", "Tip"), List.of("", "Remember"), List.of("  Background  ", " \t"))
        );

        mvc.perform(get("/api/enrich").param("q", "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hints", contains("Tip")))
                .andExpect(jsonPath("$.hints", not(hasItem(""))))
                .andExpect(jsonPath("$.reminders", contains("Remember")))
                .andExpect(jsonPath("$.background", contains("Background")));
    }

    @Test
    void enrich_filters_empty_strings_and_whitespace_chat_alias() throws Exception {
        given(retrievalService.retrieve(any())).willReturn(List.of());
        given(enrichmentService.enrich(any(), any(), any())).willReturn(
                withItems(List.of(" ", "Use streams"), List.of("  ", "Prefer Optional.ofNullable"), List.of(" ", "Docs"))
        );

        mvc.perform(get("/api/chat/enrich").param("q", "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hints", contains("Use streams")))
                .andExpect(jsonPath("$.reminders", contains("Prefer Optional.ofNullable")))
                .andExpect(jsonPath("$.background", contains("Docs")))
                .andExpect(jsonPath("$.hints", everyItem(not(blankOrNullString()))));
    }
}
