package com.williamcallahan.javachat.config;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC configuration for CORS and SPA routing.
 *
 * <p>Configures CORS for API endpoints to allow frontend dev servers
 * during development (Vite on 5173, backend on 8085), and forwards
 * SPA routes to index.html for client-side routing support.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private static final String WILDCARD_ORIGIN = "*";
    private static final String SPA_INDEX_VIEW = "forward:/index.html";

    private final List<String> allowedOrigins;
    private final List<String> allowedMethods;
    private final List<String> allowedHeaders;
    private final boolean allowCredentials;
    private final long maxAgeSeconds;

    /**
     * Creates MVC config with application properties for CORS rules.
     *
     * @param appProperties application configuration properties
     */
    public WebMvcConfig(AppProperties appProperties) {
        var cors = appProperties.getCors();
        this.allowedOrigins = cors.getAllowedOrigins();
        this.allowedMethods = cors.getAllowedMethods();
        this.allowedHeaders = cors.getAllowedHeaders();
        this.allowCredentials = cors.isAllowCredentials();
        this.maxAgeSeconds = cors.getMaxAgeSeconds();
    }

    /**
     * Configures CORS for API endpoints using the configured allow lists.
     *
     * @param registry CORS registry to update
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = allowedOrigins;
        var mapping = registry.addMapping("/api/**");
        if (origins.contains(WILDCARD_ORIGIN)) {
            mapping.allowedOriginPatterns(WILDCARD_ORIGIN);
        } else {
            mapping.allowedOrigins(origins.toArray(String[]::new));
        }
        mapping.allowedMethods(allowedMethods.toArray(String[]::new))
                .allowedHeaders(allowedHeaders.toArray(String[]::new))
                .allowCredentials(allowCredentials)
                .maxAge(maxAgeSeconds);
    }

    /**
     * Registers SPA view controllers that forward routes to index.html.
     *
     * <p>Only the routes the SPA actually publishes ({@code /}, {@code /chat},
     * {@code /guided}, {@code /learn} and their single-segment lesson paths)
     * forward to the client shell; the frontend owns canonical recovery for
     * nested lesson paths. Anything else falls through to the static resource
     * chain and the error controller, so unknown URLs return a real 404
     * instead of a soft-404 index page. Explicit depth levels are required
     * because PathPatternParser forbids {@code /**}{@code /{regex}}.
     *
     * @param registry view controller registry
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName(SPA_INDEX_VIEW);
        registry.addViewController("/chat").setViewName(SPA_INDEX_VIEW);
        registry.addViewController("/guided").setViewName(SPA_INDEX_VIEW);
        registry.addViewController("/learn").setViewName(SPA_INDEX_VIEW);
        registry.addViewController("/guided/{lessonPath:[^\\.]*}").setViewName(SPA_INDEX_VIEW);
        registry.addViewController("/learn/{lessonPath:[^\\.]*}").setViewName(SPA_INDEX_VIEW);
    }
}
