package com.williamcallahan.javachat.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SitemapController.class)
@Import({SiteUrlResolver.class, com.williamcallahan.javachat.config.AppProperties.class})
@org.springframework.security.test.context.support.WithMockUser
class SitemapControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void sitemap_uses_forwarded_headers_for_absolute_urls() throws Exception {
        mvc.perform(get("/sitemap.xml")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "java-chat.example"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
            .andExpect(content().string(containsString("<loc>https://java-chat.example/</loc>")))
            .andExpect(content().string(containsString("<loc>https://java-chat.example/chat</loc>")))
            .andExpect(content().string(containsString("<loc>https://java-chat.example/learn</loc>")))
            .andExpect(content().string(containsString("<loc>https://java-chat.example/guided</loc>")));
    }
}

