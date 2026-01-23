package com.williamcallahan.javachat.config;

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

    private final AppProperties appProperties;

    public WebMvcConfig(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var cors = appProperties.getCors();
        registry.addMapping("/api/**")
            .allowedOrigins(cors.getAllowedOrigins().toArray(String[]::new))
            .allowedMethods(cors.getAllowedMethods().toArray(String[]::new))
            .allowedHeaders(cors.getAllowedHeaders().toArray(String[]::new))
            .allowCredentials(cors.isAllowCredentials())
            .maxAge(cors.getMaxAgeSeconds());
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // SPA fallback: forward non-file routes to index.html
        // This allows client-side routing to work with direct URL access
        // Pattern [^\\.]*  matches path segments without dots (excludes static assets)
        // Explicitly define depth levels as PathPatternParser forbids /**/{regex}
        registry.addViewController("/").setViewName("forward:/index.html");
        registry.addViewController("/{path:[^\\.]*}").setViewName("forward:/index.html");
        registry.addViewController("/*/{path:[^\\.]*}").setViewName("forward:/index.html");
        registry.addViewController("/*/*/{path:[^\\.]*}").setViewName("forward:/index.html");
    }
}
