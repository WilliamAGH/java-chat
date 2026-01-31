package com.williamcallahan.javachat.adapters.in.web.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;

/**
 * Stores CSRF tokens in cookies while enforcing a fixed lifetime backed by the servlet session.
 *
 * <p>Uses the cookie repository for token transport while tracking the issued timestamp in the
 * session so tokens expire after a bounded TTL instead of living for the entire session.</p>
 */
public final class ExpiringCookieCsrfTokenRepository implements CsrfTokenRepository {
    /**
     * Request attribute set when an expired token is detected so handlers can surface a clear message.
     */
    public static final String EXPIRED_ATTRIBUTE = ExpiringCookieCsrfTokenRepository.class.getName() + ".EXPIRED";

    private static final String SESSION_TOKEN_KEY = ExpiringCookieCsrfTokenRepository.class.getName() + ".TOKEN";
    private static final String SESSION_ISSUED_AT_KEY =
            ExpiringCookieCsrfTokenRepository.class.getName() + ".ISSUED_AT";

    private final CookieCsrfTokenRepository delegate;
    private final Duration tokenTtl;
    private final Clock clock;

    /**
     * Creates a repository that expires CSRF tokens after the supplied TTL.
     *
     * @param delegate cookie-backed token repository
     * @param tokenTtl token lifetime for session-bound validation
     */
    public ExpiringCookieCsrfTokenRepository(CookieCsrfTokenRepository delegate, Duration tokenTtl) {
        this(delegate, tokenTtl, Clock.systemUTC());
    }

    ExpiringCookieCsrfTokenRepository(CookieCsrfTokenRepository delegate, Duration tokenTtl, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.tokenTtl = Objects.requireNonNull(tokenTtl, "tokenTtl");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Generates a new CSRF token using the delegate repository.
     *
     * @param request incoming HTTP request
     * @return newly generated CSRF token
     */
    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return delegate.generateToken(request);
    }

    /**
     * Persists the CSRF token in the response cookie and tracks its issue time in the session.
     *
     * @param token CSRF token to store, or null to delete
     * @param request incoming HTTP request
     * @param response outgoing HTTP response
     */
    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        if (token == null) {
            clearSessionMetadata(request);
            delegate.saveToken(null, request, response);
            return;
        }

        HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_TOKEN_KEY, token.getToken());
        session.setAttribute(SESSION_ISSUED_AT_KEY, clock.millis());
        delegate.saveToken(token, request, response);
    }

    /**
     * Loads the CSRF token when present and rejects it when the session metadata is expired.
     *
     * @param request incoming HTTP request
     * @return CSRF token if valid, otherwise null to force re-issuance
     */
    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        CsrfToken token = delegate.loadToken(request);
        if (token == null) {
            return null;
        }

        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }

        String sessionToken = readSessionToken(session);
        Long issuedAtMillis = readIssuedAtMillis(session);
        if (sessionToken == null || issuedAtMillis == null) {
            return null;
        }

        if (!sessionToken.equals(token.getToken())) {
            return null;
        }

        long nowMillis = clock.millis();
        if (isExpired(nowMillis, issuedAtMillis)) {
            markExpired(request);
            return null;
        }

        return token;
    }

    private boolean isExpired(long nowMillis, long issuedAtMillis) {
        long ageMillis = nowMillis - issuedAtMillis;
        return ageMillis > tokenTtl.toMillis();
    }

    private void clearSessionMetadata(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        session.removeAttribute(SESSION_TOKEN_KEY);
        session.removeAttribute(SESSION_ISSUED_AT_KEY);
    }

    private String readSessionToken(HttpSession session) {
        Object attribute = session.getAttribute(SESSION_TOKEN_KEY);
        if (attribute instanceof String tokenText && !tokenText.isBlank()) {
            return tokenText;
        }
        return null;
    }

    private Long readIssuedAtMillis(HttpSession session) {
        Object attribute = session.getAttribute(SESSION_ISSUED_AT_KEY);
        if (attribute instanceof Long issuedAtMillis) {
            return issuedAtMillis;
        }
        if (attribute instanceof Number issuedAtNumber) {
            return issuedAtNumber.longValue();
        }
        return null;
    }

    private void markExpired(HttpServletRequest request) {
        request.setAttribute(EXPIRED_ATTRIBUTE, Boolean.TRUE);
    }
}
