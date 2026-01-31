package com.williamcallahan.javachat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.adapters.in.web.security.CsrfAccessDeniedHandler;
import com.williamcallahan.javachat.adapters.in.web.security.CsrfTokenCookieFilter;
import com.williamcallahan.javachat.adapters.in.web.security.ExpiringCookieCsrfTokenRepository;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Security configuration for API endpoints and static resources.
 *
 * <p>Configures CORS at the Spring Security filter chain level to ensure
 * preflight OPTIONS requests are handled before authentication filters.
 */
@Configuration
public class SecurityConfig {
    private static final String WILDCARD_ORIGIN = "*";
    private static final long CSRF_TOKEN_TTL_MINUTES = 15L;

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
        source.registerCorsConfiguration("/actuator/**", config);
        return source;
    }

    /**
     * Permit all Actuator endpoints (health, info, metrics) with dev-friendly defaults.
     */
    @Bean
    @Order(0)
    public SecurityFilterChain managementSecurityFilterChain(
            HttpSecurity http, CorsConfigurationSource corsConfigurationSource) throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .cors(c -> c.configurationSource(corsConfigurationSource))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // Allow same-origin iframes (used by tab shell loading chat.html/guided.html)
                .headers(h -> h.frameOptions(fo -> fo.sameOrigin()))
                .csrf(csrf -> csrf.ignoringRequestMatchers(EndpointRequest.toAnyEndpoint()))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable());
        return http.build();
    }

    /**
     * Permits public endpoints while enforcing CSRF tokens on state-changing requests.
     *
     * <p>Uses a cookie-backed CSRF token so SPA clients can read the cookie and send the
     * matching header on POSTs, preventing cross-site requests from reusing sessions.</p>
     */
    @Bean
    @Order(1)
    public SecurityFilterChain appSecurityFilterChain(
            HttpSecurity http, CorsConfigurationSource corsConfigurationSource, ObjectMapper objectMapper)
            throws Exception {
        CookieCsrfTokenRepository cookieRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        cookieRepository.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));
        Duration csrfTokenTtl = Duration.ofMinutes(CSRF_TOKEN_TTL_MINUTES);
        ExpiringCookieCsrfTokenRepository csrfTokenRepository =
                new ExpiringCookieCsrfTokenRepository(cookieRepository, csrfTokenTtl);
        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
        CsrfAccessDeniedHandler accessDeniedHandler = new CsrfAccessDeniedHandler(objectMapper);

        http.cors(c -> c.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository).csrfTokenRequestHandler(requestHandler))
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/",
                                "/index.html",
                                "/chat.html",
                                "/guided.html",
                                "/favicon.ico",
                                "/app/**",
                                "/assets/**",
                                "/static/**")
                        .permitAll()
                        .requestMatchers("/api/**")
                        .permitAll()
                        .anyRequest()
                        .permitAll())
                // Allow same-origin iframes (used by tab shell loading chat.html/guided.html)
                .headers(h -> h.frameOptions(fo -> fo.sameOrigin()))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable());
        http.addFilterAfter(new CsrfTokenCookieFilter(), CsrfFilter.class);
        return http.build();
    }
}
