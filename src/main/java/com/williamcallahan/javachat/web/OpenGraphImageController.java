package com.williamcallahan.javachat.web;

import jakarta.annotation.security.PermitAll;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the pre-rendered Open Graph image for social media previews.
 *
 * <p>The image is rendered once at startup by {@link OpenGraphImageRenderer} and served
 * with aggressive cache headers since it never changes at runtime.
 */
@RestController
@PermitAll
@PreAuthorize("permitAll()")
public class OpenGraphImageController {

    private static final long BROWSER_CACHE_MAX_AGE_SECONDS = 86_400;
    private static final long CDN_SHARED_CACHE_MAX_AGE_SECONDS = 604_800;

    private final OpenGraphImageRenderer openGraphImageRenderer;

    /**
     * Creates the controller with the pre-rendered OG image source.
     *
     * @param openGraphImageRenderer provides the cached PNG bytes
     */
    public OpenGraphImageController(OpenGraphImageRenderer openGraphImageRenderer) {
        this.openGraphImageRenderer = openGraphImageRenderer;
    }

    /**
     * Returns the 1200x630 branded OG image as PNG with cache-friendly headers.
     *
     * @return PNG image bytes with public cache control
     */
    @GetMapping(value = "/og-image.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> serveOpenGraphImage() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(BROWSER_CACHE_MAX_AGE_SECONDS))
                        .sMaxAge(Duration.ofSeconds(CDN_SHARED_CACHE_MAX_AGE_SECONDS))
                        .cachePublic())
                .body(openGraphImageRenderer.openGraphPngBytes());
    }
}
