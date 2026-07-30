package com.williamcallahan.javachat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.adapters.in.web.security.ClerkAuthorizedPartyValidator;
import com.williamcallahan.javachat.adapters.in.web.security.CsrfAccessDeniedHandler;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
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
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    private static final String WILDCARD_ORIGIN = "*";
    private static final String HEALTH_ENDPOINT = "/actuator/health";
    private static final String LIVENESS_ENDPOINT = "/actuator/health/liveness";
    private static final String READINESS_ENDPOINT = "/actuator/health/readiness";
    private static final String DEPENDENCIES_ENDPOINT = "/actuator/health/dependencies";
    private static final String PROMETHEUS_ENDPOINT = "/actuator/prometheus";
    private static final String INFO_ENDPOINT = "/actuator/info";
    private static final String AUTHENTICATED_USER_ENDPOINT = "/api/me";
    private static final String CLERK_JWKS_URI_PROPERTY = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri";

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

    /** Permits only the container probes and Prometheus scrape surface. */
    @Bean
    @Order(0)
    public SecurityFilterChain managementSecurityFilterChain(
            HttpSecurity http, CorsConfigurationSource corsConfigurationSource) throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .cors(c -> c.configurationSource(corsConfigurationSource))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                HEALTH_ENDPOINT,
                                LIVENESS_ENDPOINT,
                                READINESS_ENDPOINT,
                                DEPENDENCIES_ENDPOINT,
                                PROMETHEUS_ENDPOINT,
                                INFO_ENDPOINT)
                        .permitAll()
                        .anyRequest()
                        .denyAll())
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
            HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource,
            ObjectMapper objectMapper,
            ObjectProvider<JwtDecoder> clerkJwtDecoder,
            AppProperties appProperties)
            throws Exception {
        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokenRepository.setCookieCustomizer(csrfCookie -> csrfCookie.sameSite("Lax"));
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
                        .requestMatchers(AUTHENTICATED_USER_ENDPOINT)
                        .authenticated()
                        .requestMatchers("/api/**")
                        .permitAll()
                        .requestMatchers("/actuator/**")
                        .denyAll()
                        .anyRequest()
                        .permitAll())
                // Allow same-origin iframes (used by tab shell loading chat.html/guided.html)
                // and enforce the per-profile Content-Security-Policy on every response.
                .headers(headers -> headers.frameOptions(frameOptions -> frameOptions.sameOrigin())
                        .contentSecurityPolicy(contentSecurityPolicy ->
                                contentSecurityPolicy.policyDirectives(appProperties.getContentSecurityPolicy())))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable());
        // Clerk is enabled per environment (dev and prod profiles): without the
        // decoder bean there is no resource server, and /api/me's authenticated()
        // rule deterministically denies every request in that deployment.
        JwtDecoder activeClerkJwtDecoder = clerkJwtDecoder.getIfAvailable();
        if (activeClerkJwtDecoder != null) {
            http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(activeClerkJwtDecoder)));
        }
        return http.build();
    }

    /**
     * Verifies Clerk session tokens against the instance JWKS with issuer and
     * authorized-party validation.
     *
     * <p>Created only when the JWKS URI property exists — the dev and prod
     * profiles each bind their own Clerk instance — so an environment without
     * Clerk configuration runs without a resource server and rejects all
     * {@code /api/me} traffic.
     *
     * <p>Built from the JWKS URI (not OIDC discovery) so application startup
     * never performs a network call; keys are fetched lazily on the first
     * bearer-token request. The extra {@code azp} check follows Clerk's manual
     * JWT verification guide: a signature-valid token minted for another
     * Clerk-backed origin must not authenticate here.
     */
    @Bean
    @ConditionalOnProperty(CLERK_JWKS_URI_PROPERTY)
    public JwtDecoder clerkJwtDecoder(
            OAuth2ResourceServerProperties resourceServerProperties, AppProperties appProperties) {
        OAuth2ResourceServerProperties.Jwt jwtProperties = resourceServerProperties.getJwt();
        NimbusJwtDecoder clerkTokenDecoder =
                NimbusJwtDecoder.withJwkSetUri(jwtProperties.getJwkSetUri()).build();
        OAuth2TokenValidator<Jwt> issuerAndTimestampValidator =
                JwtValidators.createDefaultWithIssuer(jwtProperties.getIssuerUri());
        OAuth2TokenValidator<Jwt> authorizedPartyValidator =
                new ClerkAuthorizedPartyValidator(appProperties.getClerk().getAuthorizedParties());
        clerkTokenDecoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(issuerAndTimestampValidator, authorizedPartyValidator));
        return clerkTokenDecoder;
    }
}
