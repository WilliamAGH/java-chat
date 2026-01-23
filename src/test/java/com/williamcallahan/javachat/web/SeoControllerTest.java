package com.williamcallahan.javachat.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SeoController.class)
@Import({SiteUrlResolver.class, com.williamcallahan.javachat.config.AppProperties.class})
@WithMockUser
class SeoControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void serves_root_with_seo_metadata() throws Exception {
        mvc.perform(get("/")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "example.com"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string(containsString("<title>Java Chat - AI-Powered Java Learning With Citations</title>")))
            .andExpect(content().string(containsString("og:url\" content=\"https://example.com\"")));
    }

    @Test
    void serves_chat_with_specific_metadata() throws Exception {
        mvc.perform(get("/chat")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "example.com"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("<title>Java Chat - Streaming Java Tutor With Citations</title>")))
            .andExpect(content().string(containsString("og:url\" content=\"https://example.com/chat\"")));
    }

    @Test
    void serves_guided_with_specific_metadata() throws Exception {
        mvc.perform(get("/guided")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "example.com"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("<title>Guided Java Learning - Java Chat</title>")))
            .andExpect(content().string(containsString("og:description\" content=\"Structured, step-by-step Java learning paths")))
            .andExpect(content().string(containsString("og:url\" content=\"https://example.com/guided\"")));
    }
}
