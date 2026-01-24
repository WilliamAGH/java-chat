package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.service.MarkdownService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// removed unused imports
import org.springframework.http.MediaType;
// removed unused imports
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration coverage for markdown rendering endpoints with WebMvcTest wiring.
 */
@org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest(controllers = MarkdownApiIntegrationTest.TestMarkdownController.class)
@org.springframework.test.context.ContextConfiguration(classes = {MarkdownApiIntegrationTest.TestMarkdownController.class, com.williamcallahan.javachat.service.MarkdownService.class, com.williamcallahan.javachat.service.markdown.UnifiedMarkdownService.class})
@org.springframework.security.test.context.support.WithMockUser
class MarkdownApiIntegrationTest {

    @Autowired
    org.springframework.test.web.servlet.MockMvc mvc;

    @Test
    void closingFenceProseIsOutsideCode_viaApi() throws Exception {
        String input = "Here's an example:```java\nint x = 10 % 3;\n```The result is 1.";
        String payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("text", input));
        String html = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/__test/markdown")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("<pre>"));
        int codeClose = html.indexOf("</code></pre>");
        int theIdx = html.indexOf("The", codeClose + 1);
        int restIdx = html.indexOf("result is 1.", codeClose + 1);
        assertTrue(codeClose >= 0 && theIdx > codeClose && restIdx > codeClose, "Prose must be outside the code block");
    }

    /**
     * Minimal controller used to expose markdown rendering for integration tests.
     */
    @RestController
    static class TestMarkdownController {
        private final MarkdownService markdownService;

        /**
         * Creates a test controller wired with the markdown service.
         *
         * @param markdownService service used to render markdown
         */
        TestMarkdownController(MarkdownService markdownService) {
            this.markdownService = markdownService;
        }

        /**
         * Renders structured markdown HTML for API test coverage.
         *
         * @param body request payload containing markdown text
         * @return rendered HTML string
         */
        @PostMapping("/__test/markdown")
        public String render(@RequestBody Map<String, String> body) {
            String text = body.getOrDefault("text", "");
            return markdownService.processStructured(text).html();
        }
    }
}
