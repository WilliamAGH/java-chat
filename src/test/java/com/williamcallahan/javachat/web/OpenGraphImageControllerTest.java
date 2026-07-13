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
    private static final String OG_IMAGE_PATH = "/og-image.png";
    private static final String CACHE_CONTROL_HEADER_NAME = "Cache-Control";
    private static final String CACHE_CONTROL_PUBLIC_TOKEN = "public";
    private static final String CACHE_CONTROL_MAX_AGE_TOKEN = "max-age=86400";
    private static final String OG_IMAGE_DECODE_ASSERTION_MESSAGE = "Response must contain a decodable image";
    private static final String OG_IMAGE_WIDTH_ASSERTION_MESSAGE =
            "OG image width must be " + OpenGraphImageRenderer.OG_IMAGE_WIDTH;
    private static final String OG_IMAGE_HEIGHT_ASSERTION_MESSAGE =
            "OG image height must be " + OpenGraphImageRenderer.OG_IMAGE_HEIGHT;

    @Autowired
    MockMvc mvc;

    @Test
    void serves_og_image_with_correct_content_type_and_cache_headers() throws Exception {
        mvc.perform(get(OG_IMAGE_PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG))
                .andExpect(header().string(
                                CACHE_CONTROL_HEADER_NAME,
                                org.hamcrest.Matchers.containsString(CACHE_CONTROL_MAX_AGE_TOKEN)))
                .andExpect(header().string(
                                CACHE_CONTROL_HEADER_NAME,
                                org.hamcrest.Matchers.containsString(CACHE_CONTROL_PUBLIC_TOKEN)));
    }

    @Test
    void serves_og_image_with_correct_dimensions() throws Exception {
        MvcResult mvcOutcome =
                mvc.perform(get(OG_IMAGE_PATH)).andExpect(status().isOk()).andReturn();

        byte[] pngBytes = mvcOutcome.getResponse().getContentAsByteArray();
        BufferedImage decodedImage = ImageIO.read(new ByteArrayInputStream(pngBytes));

        assertNotNull(decodedImage, OG_IMAGE_DECODE_ASSERTION_MESSAGE);
        assertEquals(OpenGraphImageRenderer.OG_IMAGE_WIDTH, decodedImage.getWidth(), OG_IMAGE_WIDTH_ASSERTION_MESSAGE);
        assertEquals(
                OpenGraphImageRenderer.OG_IMAGE_HEIGHT, decodedImage.getHeight(), OG_IMAGE_HEIGHT_ASSERTION_MESSAGE);
    }
}
