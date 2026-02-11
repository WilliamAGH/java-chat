package com.williamcallahan.javachat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Verifies the OG image endpoint returns correct content type, cache headers, and image dimensions.
 */
@WebMvcTest(controllers = OpenGraphImageController.class)
@Import({OpenGraphImageRenderer.class, com.williamcallahan.javachat.config.AppProperties.class})
@WithMockUser
class OpenGraphImageControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void serves_og_image_with_correct_content_type_and_cache_headers() throws Exception {
        mvc.perform(get("/og-image.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=86400")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("public")));
    }

    @Test
    void serves_og_image_with_correct_dimensions() throws Exception {
        MvcResult mvcOutcome =
                mvc.perform(get("/og-image.png")).andExpect(status().isOk()).andReturn();

        byte[] pngBytes = mvcOutcome.getResponse().getContentAsByteArray();
        BufferedImage decodedImage = ImageIO.read(new ByteArrayInputStream(pngBytes));

        assertNotNull(decodedImage, "Response must contain a decodable image");
        assertEquals(1200, decodedImage.getWidth(), "OG image width must be 1200");
        assertEquals(630, decodedImage.getHeight(), "OG image height must be 630");
    }
}
