package com.williamcallahan.javachat.web;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Renders a branded 1200x630 Open Graph image for social media previews.
 *
 * <p>The image is rendered once at startup using the high-resolution logo from classpath resources.
 * The result is cached as a byte array since the image is deterministic and never changes at runtime.
 */
@Component
public class OpenGraphImageRenderer {

    static final int OG_IMAGE_WIDTH = 1200;
    static final int OG_IMAGE_HEIGHT = 630;
    static final String OG_IMAGE_CONTENT_TYPE = "image/png";
    private static final int LOGO_SIZE = 380;

    private static final Color DARK_BACKGROUND = new Color(0x1a, 0x1a, 0x18);
    private static final Color WARM_ACCENT = new Color(0x3a, 0x30, 0x18);
    private static final Color TITLE_COLOR = Color.WHITE;
    private static final Color MUTED_TAGLINE = new Color(0x99, 0x99, 0x88);

    private static final String TITLE_TEXT = "Java Chat";
    private static final int TITLE_FONT_SIZE = 72;
    private static final String TAGLINE_TEXT = "AI-Powered Java Learning With Citations";
    private static final int TAGLINE_FONT_SIZE = 26;
    private static final int TEXT_LEFT_MARGIN = 80;
    private static final int LOGO_RIGHT_MARGIN = 100;
    private static final int TITLE_BASELINE_OFFSET = 10;
    private static final int TAGLINE_VERTICAL_GAP = 20;

    private final byte[] openGraphPngBytes;

    /**
     * Loads the logo and renders the OG image at construction time.
     *
     * @param logoResource the high-resolution logo PNG from classpath
     * @throws IOException if the logo cannot be read or the image cannot be encoded
     */
    public OpenGraphImageRenderer(
            @Value("classpath:/static/assets/javachat_brace_cup_star_1024.png") Resource logoResource)
            throws IOException {
        BufferedImage logoSource = ImageIO.read(logoResource.getInputStream());
        this.openGraphPngBytes = renderOpenGraphImage(logoSource);
    }

    /**
     * Returns the pre-rendered OG image as PNG bytes.
     *
     * @return PNG-encoded byte array of the 1200x630 image
     */
    public byte[] openGraphPngBytes() {
        return openGraphPngBytes;
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
        GradientPaint backgroundGradient =
                new GradientPaint(0, 0, DARK_BACKGROUND, OG_IMAGE_WIDTH, OG_IMAGE_HEIGHT, WARM_ACCENT);
        graphics.setPaint(backgroundGradient);
        graphics.fillRect(0, 0, OG_IMAGE_WIDTH, OG_IMAGE_HEIGHT);
    }

    private void drawLogo(Graphics2D graphics, BufferedImage logoSource) {
        int logoX = OG_IMAGE_WIDTH - LOGO_SIZE - LOGO_RIGHT_MARGIN;
        int logoY = (OG_IMAGE_HEIGHT - LOGO_SIZE) / 2;
        graphics.drawImage(logoSource, logoX, logoY, LOGO_SIZE, LOGO_SIZE, null);
    }

    private void drawText(Graphics2D graphics) {
        Font titleFont = new Font(Font.SANS_SERIF, Font.BOLD, TITLE_FONT_SIZE);
        graphics.setFont(titleFont);
        graphics.setColor(TITLE_COLOR);

        int textVerticalCenter = OG_IMAGE_HEIGHT / 2;
        int titleBaseline = textVerticalCenter - TITLE_BASELINE_OFFSET;
        graphics.drawString(TITLE_TEXT, TEXT_LEFT_MARGIN, titleBaseline);

        Font taglineFont = new Font(Font.SANS_SERIF, Font.PLAIN, TAGLINE_FONT_SIZE);
        graphics.setFont(taglineFont);
        graphics.setColor(MUTED_TAGLINE);

        int taglineBaseline = titleBaseline + TAGLINE_FONT_SIZE + TAGLINE_VERTICAL_GAP;
        graphics.drawString(TAGLINE_TEXT, TEXT_LEFT_MARGIN, taglineBaseline);
    }

    private byte[] encodePng(BufferedImage canvas) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(canvas, "png", outputStream);
        return outputStream.toByteArray();
    }
}
