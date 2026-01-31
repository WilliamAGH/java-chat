package com.williamcallahan.javachat.adapters.in.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ensures a CSRF token cookie is issued on initial page loads for SPA clients.
 *
 * <p>The CSRF token repository generates and saves the token only when the token is
 * accessed. Triggering token access on safe requests allows clients to read the
 * cookie and include the header before their first POST.</p>
 */
public final class CsrfTokenCookieFilter extends OncePerRequestFilter {

    /**
     * Forces CSRF token generation so the response includes the cookie for the client.
     *
     * @param request incoming HTTP request
     * @param response outgoing HTTP response
     * @param filterChain remaining filter chain
     * @throws ServletException for filter errors
     * @throws IOException for IO errors
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Object csrfAttribute = request.getAttribute(CsrfToken.class.getName());
        if (csrfAttribute instanceof CsrfToken csrfToken) {
            csrfToken.getToken();
        }
        filterChain.doFilter(request, response);
    }
}
