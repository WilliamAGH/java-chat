package com.williamcallahan.javachat.adapters.in.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.williamcallahan.javachat.application.auth.ApiKeyLifecycle;
import com.williamcallahan.javachat.application.auth.ApiKeyOperationUnavailableException;
import com.williamcallahan.javachat.application.auth.VerifiedApiKey;
import com.williamcallahan.javachat.domain.errors.ApiErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates callers presenting a personal Clerk API key as a bearer token.
 *
 * <p>Exists so non-browser clients — the Java Chat CLI above all — can reach the
 * same API as the signed-in website without a cookie session. Browser traffic is
 * untouched: a request without an {@code ak_} bearer passes straight through to
 * the Clerk session-token lane.
 *
 * <p>The verified key becomes a secret-free authentication token so downstream
 * endpoints consume the same credential-neutral subject as Clerk session tokens.
 */
public final class ClerkApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ClerkApiKeyAuthenticationFilter.class);
    private static final DefaultBearerTokenResolver BEARER_TOKEN_RESOLVER = new DefaultBearerTokenResolver();
    private static final int CLERK_API_KEY_MAXIMUM_LENGTH = 512;
    private static final int CLERK_VERIFICATION_MAXIMUM_REQUESTS_PER_MINUTE = 20;
    private static final int CLERK_VERIFICATION_CLIENT_BUDGET_MAXIMUM_ENTRIES = 10_000;
    private static final java.time.Duration CLERK_VERIFICATION_WINDOW = java.time.Duration.ofMinutes(1);
    private static final String AUTHENTICATED_USER_PATH = "/api/me";
    private static final String API_KEY_REVOCATION_PATH = "/api/me/api-key";
    private static final String CHAT_STREAM_PATH = "/api/chat/stream";
    private static final String REJECTED_KEY_MESSAGE = "API key is invalid, revoked, or expired.";
    private static final String VERIFICATION_UNAVAILABLE_MESSAGE =
            "API key verification is temporarily unavailable. Please retry.";

    private final ApiKeyLifecycle apiKeyLifecycle;
    private final ObjectMapper objectMapper;
    private final Cache<String, AtomicInteger> clientVerificationCounts = Caffeine.newBuilder()
            .expireAfterWrite(CLERK_VERIFICATION_WINDOW)
            .maximumSize(CLERK_VERIFICATION_CLIENT_BUDGET_MAXIMUM_ENTRIES)
            .build();

    /**
     * Creates the filter with the Clerk boundary and shared JSON serializer.
     *
     * @param apiKeyLifecycle application-owned verification boundary
     * @param objectMapper Spring-managed JSON mapper used for error responses
     */
    public ClerkApiKeyAuthenticationFilter(ApiKeyLifecycle apiKeyLifecycle, ObjectMapper objectMapper) {
        this.apiKeyLifecycle = Objects.requireNonNull(apiKeyLifecycle, "apiKeyLifecycle");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Reports whether a request carries a Clerk API key bearer token.
     *
     * <p>Shared with the CSRF configuration, which must classify the credential
     * from the raw request: Spring Security evaluates CSRF before any
     * authentication filter runs, so no verified identity exists at that point.
     *
     * @param request incoming HTTP request
     * @return true when the {@code Authorization} header carries an {@code ak_} bearer
     */
    public static boolean isClerkApiKeyRequest(HttpServletRequest request) {
        return presentedApiKey(request).isPresent();
    }

    /**
     * Verifies a presented API key and installs the resulting principal.
     *
     * <p>A presented-but-rejected key ends the request with {@code 401} rather
     * than continuing anonymously: silently downgrading a supplied credential
     * would hide revocation from the caller.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Optional<String> presentedSecret = presentedApiKey(request);
        if (presentedSecret.isEmpty() || hasExistingAuthentication()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!reserveClientVerification(request.getRemoteAddr())) {
            writeError(response, HttpStatus.TOO_MANY_REQUESTS, "API key verification rate limit exceeded.");
            return;
        }
        Optional<VerifiedApiKey> verifiedKey;
        try {
            verifiedKey = apiKeyLifecycle.verify(presentedSecret.get());
        } catch (ApiKeyOperationUnavailableException verificationFailure) {
            log.error("Clerk API key verification was unavailable", verificationFailure);
            writeError(response, HttpStatus.SERVICE_UNAVAILABLE, VERIFICATION_UNAVAILABLE_MESSAGE);
            return;
        }
        if (verifiedKey.isEmpty()) {
            writeError(response, HttpStatus.UNAUTHORIZED, REJECTED_KEY_MESSAGE);
            return;
        }

        installAuthentication(verifiedKey.get());
        filterChain.doFilter(request, response);
    }

    private void installAuthentication(VerifiedApiKey verifiedKey) {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new ClerkApiKeyAuthenticationToken(verifiedKey.id(), verifiedKey.subject()));
        SecurityContextHolder.setContext(securityContext);
    }

    private static boolean hasExistingAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication() != null;
    }

    private boolean reserveClientVerification(String clientAddress) {
        AtomicInteger verificationCount =
                clientVerificationCounts.get(clientAddress, ignoredAddress -> new AtomicInteger());
        return verificationCount.incrementAndGet() <= CLERK_VERIFICATION_MAXIMUM_REQUESTS_PER_MINUTE;
    }

    private static Optional<String> presentedApiKey(HttpServletRequest request) {
        if (!AUTHENTICATED_USER_PATH.equals(request.getRequestURI())
                && !API_KEY_REVOCATION_PATH.equals(request.getRequestURI())
                && !CHAT_STREAM_PATH.equals(request.getRequestURI())) {
            return Optional.empty();
        }
        String bearerToken = BEARER_TOKEN_RESOLVER.resolve(request);
        if (bearerToken == null) {
            return Optional.empty();
        }
        return bearerToken.startsWith("ak_") && bearerToken.length() <= CLERK_API_KEY_MAXIMUM_LENGTH
                ? Optional.of(bearerToken)
                : Optional.empty();
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiErrorResponse.error(message));
    }
}
