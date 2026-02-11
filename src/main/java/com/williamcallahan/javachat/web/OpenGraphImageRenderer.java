package com.williamcallahan.javachat.web;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Renders a branded 1200x630 Open Graph image for social media previews.
 *
 * <p>The image is rendered once at startup using the high-resolution logo from classpath resources.
 * The background uses the exact favicon navy blue (#25263C) so the logo's transparent rounded
 * corners blend seamlessly. The result is cached as a byte array since the image is deterministic
 * and never changes at runtime.
 */
@Component
public class OpenGraphImageRenderer {

    static final int OG_IMAGE_WIDTH = 1200;
    static final int OG_IMAGE_HEIGHT = 630;
    static final String OG_IMAGE_CONTENT_TYPE = "image/png";
    static final String OG_IMAGE_ENCODING_FORMAT = "png";
    static final String OG_LOGO_RESOURCE_PATH = "classpath:/branding/javachat_brace_cup_star_1024.png";
    private static final String OG_RENDER_FAILURE_MESSAGE_PREFIX =
            "Failed to render Open Graph image from logo resource: ";
    private static final String OG_UNREADABLE_LOGO_MESSAGE_PREFIX = "Logo resource is not a readable image: ";
    private static final String OG_PNG_WRITER_MISSING_MESSAGE = "No ImageIO writer available for PNG format";

    /** Exact background color from the favicon icon for seamless blending. */
    private static final Color FAVICON_BLUE_BACKGROUND = new Color(0x25, 0x26, 0x3C);

    private static final Color TITLE_COLOR = Color.WHITE;
    private static final Color TAGLINE_COLOR = new Color(0xB8, 0xB9, 0xCC);

    private static final String TITLE_TEXT = "JavaChat.ai";
    private static final int TITLE_FONT_SIZE = 82;

    private static final String[] TAGLINE_LINES = {
        "Learn programming and chat", "directly with libraries, SDKs,", "and GitHub repositories"
    };
    private static final int TAGLINE_FONT_SIZE = 34;
    private static final int TAGLINE_LINE_SPACING = 48;

    private static final int TEXT_LEFT_MARGIN = 80;
    private static final int TITLE_TOP_MARGIN = 170;
    private static final int TITLE_TAGLINE_SPACING = 30;
    private static final int LOGO_RIGHT_MARGIN = 80;

    /**
     * Pixels to crop from each edge of the 1024px source to fully remove the rounded-rectangle
     * border and its semi-transparent white fringe pixels that create a visible outline.
     */
    private static final int LOGO_CROP_INSET = 110;

    private static final int LOGO_DISPLAY_HEIGHT = 340;

    private final byte[] openGraphPngBytes;

    /**
     * Loads the logo and renders the OG image at construction time.
     *
     * <p>Fails fast with an unchecked exception if the logo resource cannot be read or the
     * image cannot be encoded, since the application cannot serve valid social previews without it.
     *
     * @param logoResource the high-resolution logo PNG from classpath
     */
    public OpenGraphImageRenderer(@Value(OG_LOGO_RESOURCE_PATH) Resource logoResource) {
        try (InputStream logoStream = logoResource.getInputStream()) {
            BufferedImage logoSource = ImageIO.read(logoStream);
            if (logoSource == null) {
                throw new IOException(OG_UNREADABLE_LOGO_MESSAGE_PREFIX + logoResource.getDescription());
            }
            this.openGraphPngBytes = renderOpenGraphImage(logoSource);
        } catch (IOException logoLoadException) {
            throw new UncheckedIOException(
                    OG_RENDER_FAILURE_MESSAGE_PREFIX + logoResource.getDescription(), logoLoadException);
        }
    }

    /**
     * Returns a defensive copy of the pre-rendered OG image as PNG bytes.
     *
     * @return PNG-encoded byte array of the 1200x630 image
     */
    public byte[] openGraphPngBytes() {
        return openGraphPngBytes.clone();
    }

    private byte[] renderOpenGraphImage(BufferedImage logoSource) throws IOException {
        BufferedImage canvas = new BufferedImage(OG_IMAGE_WIDTH, OG_IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = canvas.createGraphics();

        try {
            configureRenderingQuality(graphics);
            drawBackground(graphics);
            drawLogo(graphics, logoSource);
            drawText(graphics);
        } finally {
            graphics.dispose();
        }

        return encodePng(canvas);
    }

    private void configureRenderingQuality(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private void drawBackground(Graphics2D graphics) {
        graphics.setColor(FAVICON_BLUE_BACKGROUND);
        graphics.fillRect(0, 0, OG_IMAGE_WIDTH, OG_IMAGE_HEIGHT);
    }

    private void drawLogo(Graphics2D graphics, BufferedImage logoSource) {
        BufferedImage croppedLogo = logoSource.getSubimage(
                LOGO_CROP_INSET,
                LOGO_CROP_INSET,
                logoSource.getWidth() - 2 * LOGO_CROP_INSET,
                logoSource.getHeight() - 2 * LOGO_CROP_INSET);

        double scale = (double) LOGO_DISPLAY_HEIGHT / croppedLogo.getHeight();
        int displayWidth = (int) (croppedLogo.getWidth() * scale);

        int logoX = OG_IMAGE_WIDTH - displayWidth - LOGO_RIGHT_MARGIN;
        int logoY = (OG_IMAGE_HEIGHT - LOGO_DISPLAY_HEIGHT) / 2;
        graphics.drawImage(croppedLogo, logoX, logoY, displayWidth, LOGO_DISPLAY_HEIGHT, null);
    }

    private void drawText(Graphics2D graphics) {
        Font titleFont = new Font(Font.SANS_SERIF, Font.BOLD, TITLE_FONT_SIZE);
        graphics.setFont(titleFont);
        graphics.setColor(TITLE_COLOR);

        FontMetrics titleMetrics = graphics.getFontMetrics();
        int titleBaseline = TITLE_TOP_MARGIN + titleMetrics.getAscent();
        graphics.drawString(TITLE_TEXT, TEXT_LEFT_MARGIN, titleBaseline);

        Font taglineFont = new Font(Font.SANS_SERIF, Font.PLAIN, TAGLINE_FONT_SIZE);
        graphics.setFont(taglineFont);
        graphics.setColor(TAGLINE_COLOR);

        int taglineBaseline = titleBaseline + TITLE_TAGLINE_SPACING;
        for (String taglineLine : TAGLINE_LINES) {
            taglineBaseline += TAGLINE_LINE_SPACING;
            graphics.drawString(taglineLine, TEXT_LEFT_MARGIN, taglineBaseline);
        }
    }

    private byte[] encodePng(BufferedImage canvas) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        boolean imageWritten = ImageIO.write(canvas, OG_IMAGE_ENCODING_FORMAT, outputStream);
        if (!imageWritten) {
            throw new IOException(OG_PNG_WRITER_MISSING_MESSAGE);
        }
        return outputStream.toByteArray();
    }
}
