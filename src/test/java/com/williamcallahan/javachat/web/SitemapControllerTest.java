package com.williamcallahan.javachat.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies sitemap XML uses the configured public base URL.
 */
@WebMvcTest(controllers = SitemapController.class)
@Import({SiteUrlResolver.class, com.williamcallahan.javachat.config.AppProperties.class})
@TestPropertySource(properties = "app.public-base-url=https://java-chat.example")
@org.springframework.security.test.context.support.WithMockUser
class SitemapControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void sitemap_uses_configured_public_base_url_for_absolute_urls() throws Exception {
        mvc.perform(get("/sitemap.xml")
            )
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
            .andExpect(content().string(containsString("<loc>https://java-chat.example/</loc>")))
            .andExpect(content().string(containsString("<loc>https://java-chat.example/chat</loc>")))
            .andExpect(content().string(containsString("<loc>https://java-chat.example/learn</loc>")))
            .andExpect(content().string(containsString("<loc>https://java-chat.example/guided</loc>")));
    }
}
