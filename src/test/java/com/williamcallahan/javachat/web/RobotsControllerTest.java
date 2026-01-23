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

@WebMvcTest(controllers = RobotsController.class)
@Import({SiteUrlResolver.class, com.williamcallahan.javachat.config.AppProperties.class})
@org.springframework.security.test.context.support.WithMockUser
class RobotsControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void robots_points_to_sitemap_and_disallows_api_paths() throws Exception {
        mvc.perform(get("/robots.txt")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "java-chat.example"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
            .andExpect(content().string(containsString("Disallow: /api/")))
            .andExpect(content().string(containsString("Disallow: /actuator/")))
            .andExpect(content().string(containsString("Sitemap: https://java-chat.example/sitemap.xml")));
    }
}

