package com.williamcallahan.javachat.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
@TestPropertySource(properties = OpenGraphImageRendererTest.SPRING_MAIN_BANNER_MODE_OFF_PROPERTY)
class OpenGraphImageRendererTest {
    static final String SPRING_MAIN_BANNER_MODE_OFF_PROPERTY = "spring.main.banner-mode=off";
    private static final String OG_IMAGE_BYTES_NOT_NULL_ASSERTION_MESSAGE = "Rendered PNG bytes must not be null";
    private static final String OG_IMAGE_BYTES_NOT_EMPTY_ASSERTION_MESSAGE = "Rendered PNG must not be empty";
    private static final String OG_IMAGE_DECODE_ASSERTION_MESSAGE = "Bytes must decode to a valid image";
    private static final String OG_IMAGE_WIDTH_ASSERTION_MESSAGE =
            "OG image width must be " + OpenGraphImageRenderer.OG_IMAGE_WIDTH;
    private static final String OG_IMAGE_HEIGHT_ASSERTION_MESSAGE =
            "OG image height must be " + OpenGraphImageRenderer.OG_IMAGE_HEIGHT;
    private static final String OG_IMAGE_DEFENSIVE_COPY_ASSERTION_MESSAGE =
            "Renderer must return a defensive copy, not the internal array";
    private static final String OG_IMAGE_CONTENT_EQUALITY_ASSERTION_MESSAGE =
            "Repeated calls must return identical content";

    private final OpenGraphImageRenderer renderer;

    @Autowired
    OpenGraphImageRendererTest(OpenGraphImageRenderer renderer) {
        this.renderer = renderer;
    }

    @Test
    void renders_valid_png_with_correct_dimensions() throws Exception {
        byte[] pngBytes = renderer.openGraphPngBytes();

        assertNotNull(pngBytes, OG_IMAGE_BYTES_NOT_NULL_ASSERTION_MESSAGE);
        assertTrue(pngBytes.length > 0, OG_IMAGE_BYTES_NOT_EMPTY_ASSERTION_MESSAGE);

        BufferedImage decodedImage = ImageIO.read(new ByteArrayInputStream(pngBytes));
        assertNotNull(decodedImage, OG_IMAGE_DECODE_ASSERTION_MESSAGE);
        assertEquals(OpenGraphImageRenderer.OG_IMAGE_WIDTH, decodedImage.getWidth(), OG_IMAGE_WIDTH_ASSERTION_MESSAGE);
        assertEquals(
                OpenGraphImageRenderer.OG_IMAGE_HEIGHT, decodedImage.getHeight(), OG_IMAGE_HEIGHT_ASSERTION_MESSAGE);
    }

    @Test
    void returns_defensive_copy_with_identical_content_on_repeated_calls() {
        byte[] firstCall = renderer.openGraphPngBytes();
        byte[] secondCall = renderer.openGraphPngBytes();

        assertNotSame(firstCall, secondCall, OG_IMAGE_DEFENSIVE_COPY_ASSERTION_MESSAGE);
        assertArrayEquals(firstCall, secondCall, OG_IMAGE_CONTENT_EQUALITY_ASSERTION_MESSAGE);
    }
}
