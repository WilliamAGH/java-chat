package com.williamcallahan.javachat.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC configuration for CORS and SPA routing.
 *
 * <p>Configures CORS for API endpoints to allow Vite dev server (port 5173)
 * during development, and forwards SPA routes to index.html.
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
        registry.addViewController("/").setViewName("forward:/index.html");
    }
}
