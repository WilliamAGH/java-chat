package com.williamcallahan.javachat.config;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.List;

/**
 * Security configuration for API endpoints and static resources.
 *
 * <p>Configures CORS at the Spring Security filter chain level to ensure
 * preflight OPTIONS requests are handled before authentication filters.
 */
@Configuration
public class SecurityConfig {
    private static final String WILDCARD_ORIGIN = "*";

    /**
     * CORS configuration source for Spring Security filter chain integration.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(AppProperties appProperties) {
        var cors = appProperties.getCors();
        List<String> allowedOrigins = cors.getAllowedOrigins();
        CorsConfiguration config = new CorsConfiguration();
        if (allowedOrigins.contains(WILDCARD_ORIGIN)) {
            config.setAllowedOriginPatterns(List.of(WILDCARD_ORIGIN));
        } else {
            config.setAllowedOrigins(allowedOrigins);
        }
        config.setAllowedMethods(cors.getAllowedMethods());
        config.setAllowedHeaders(cors.getAllowedHeaders());
        config.setAllowCredentials(cors.isAllowCredentials());
        config.setMaxAge(cors.getMaxAgeSeconds());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    /**
     * Permit all Actuator endpoints (health, info, metrics) with dev-friendly defaults.
     */
    @Bean
    @Order(0)
    public SecurityFilterChain managementSecurityFilterChain(HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
            .securityMatcher(EndpointRequest.toAnyEndpoint())
            .cors(c -> c.configurationSource(corsConfigurationSource))
            .authorizeHttpRequests(auth -> auth
                .anyRequest().permitAll()
            )
            // Allow same-origin iframes (used by tab shell loading chat.html/guided.html)
            .headers(h -> h.frameOptions(fo -> fo.sameOrigin()))
            .csrf(csrf -> csrf.ignoringRequestMatchers(EndpointRequest.toAnyEndpoint()))
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable());
        return http.build();
    }

    /**
     * Permit all application endpoints; CSRF disabled for stateless APIs.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain appSecurityFilterChain(HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
            .cors(c -> c.configurationSource(corsConfigurationSource))
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/",
                    "/index.html",
                    "/chat.html",
                    "/guided.html",
                    "/favicon.ico",
                    "/app/**",
                    "/assets/**",
                    "/static/**"
                ).permitAll()
                .requestMatchers("/api/**").permitAll()
                .anyRequest().permitAll()
            )
            // Allow same-origin iframes (used by tab shell loading chat.html/guided.html)
            .headers(h -> h.frameOptions(fo -> fo.sameOrigin()))
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable());
        return http.build();
    }
}
