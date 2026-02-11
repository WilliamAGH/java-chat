package com.williamcallahan.javachat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies that the OG image renderer produces a valid 1200x630 PNG.
 */
@SpringBootTest(classes = OpenGraphImageRenderer.class)
@TestPropertySource(properties = "spring.main.banner-mode=off")
class OpenGraphImageRendererTest {

    @Autowired
    OpenGraphImageRenderer renderer;

    @Test
    void renders_valid_png_with_correct_dimensions() throws Exception {
        byte[] pngBytes = renderer.openGraphPngBytes();

        assertNotNull(pngBytes, "Rendered PNG bytes must not be null");
        assertTrue(pngBytes.length > 0, "Rendered PNG must not be empty");

        BufferedImage decodedImage = ImageIO.read(new ByteArrayInputStream(pngBytes));
        assertNotNull(decodedImage, "Bytes must decode to a valid image");
        assertEquals(1200, decodedImage.getWidth(), "OG image width must be 1200");
        assertEquals(630, decodedImage.getHeight(), "OG image height must be 630");
    }

    @Test
    void returns_same_cached_bytes_on_repeated_calls() {
        byte[] firstCall = renderer.openGraphPngBytes();
        byte[] secondCall = renderer.openGraphPngBytes();

        assertTrue(firstCall == secondCall, "Renderer must return the same cached byte array instance");
    }
}
