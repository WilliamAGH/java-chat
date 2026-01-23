package com.williamcallahan.javachat.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Resolves the public-facing base URL for the current request.
 *
 * <p>This is used for SEO endpoints (robots.txt, sitemap.xml) where absolute URLs are preferred.
 * It supports common reverse proxy headers (X-Forwarded-Proto/Host/Port) and falls back to the
 * servlet request values when those headers are not present.
 */
@Component
public class SiteUrlResolver {

    private static final String HDR_FORWARDED_PROTO = "X-Forwarded-Proto";
    private static final String HDR_FORWARDED_HOST = "X-Forwarded-Host";
    private static final String HDR_FORWARDED_PORT = "X-Forwarded-Port";

    private static final String SCHEME_HTTPS = "https";
    private static final int DEFAULT_HTTPS_PORT = 443;
    private static final int DEFAULT_HTTP_PORT = 80;
    private static final int MIN_VALID_PORT = 1;
    private static final int MAX_VALID_PORT = 65535;
    private static final String SCHEME_SEPARATOR = "://";
    private static final char PORT_SEPARATOR = ':';

    /**
     * Resolves the request's public base URL (scheme + host [+ port]).
     *
     * @param request current HTTP request
     * @return base URL like {@code https://example.com}
     */
    public String resolvePublicBaseUrl(HttpServletRequest request) {
        Optional<String> forwardedProto = firstHeaderToken(request, HDR_FORWARDED_PROTO);
        String scheme = forwardedProto.orElseGet(request::getScheme);
        String hostHeader = firstHeaderToken(request, HDR_FORWARDED_HOST).orElseGet(request::getServerName);
        Optional<Integer> forwardedPort = firstHeaderToken(request, HDR_FORWARDED_PORT).flatMap(SiteUrlResolver::parsePort);

        // If X-Forwarded-Host already includes a port (common in some proxies), keep it as-is.
        // This is intentionally conservative to avoid breaking IPv6 bracketed hosts.
        if (hostHeader.indexOf(PORT_SEPARATOR) >= 0 && forwardedPort.isEmpty()) {
            return scheme + SCHEME_SEPARATOR + hostHeader;
        }

        // When X-Forwarded-Proto is present but X-Forwarded-Port is not, assume the default port
        // for the forwarded scheme. The request's server port would be the internal proxy port,
        // not the external-facing port, so we shouldn't use it.
        boolean hasForwardedScheme = forwardedProto.isPresent();
        if (hasForwardedScheme && forwardedPort.isEmpty()) {
            // Proxy handles TLS termination; use default port for scheme (no explicit port in URL)
            return scheme + SCHEME_SEPARATOR + hostHeader;
        }

        int port = forwardedPort.orElseGet(request::getServerPort);
        boolean defaultPort = isDefaultPort(scheme, port);
        String authority = defaultPort ? hostHeader : hostHeader + PORT_SEPARATOR + port;
        return scheme + SCHEME_SEPARATOR + authority;
    }

    private static Optional<String> firstHeaderToken(HttpServletRequest request, String headerName) {
        String raw = request.getHeader(headerName);
        if (raw == null) {
            return Optional.empty();
        }

        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }

        int commaIndex = trimmed.indexOf(',');
        String token = commaIndex >= 0 ? trimmed.substring(0, commaIndex).trim() : trimmed;
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    private static Optional<Integer> parsePort(String rawPort) {
        try {
            int port = Integer.parseInt(rawPort);
            if (port < MIN_VALID_PORT || port > MAX_VALID_PORT) {
                return Optional.empty();
            }
            return Optional.of(port);
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static boolean isDefaultPort(String scheme, int port) {
        if (SCHEME_HTTPS.equalsIgnoreCase(scheme)) {
            return port == DEFAULT_HTTPS_PORT;
        }
        return port == DEFAULT_HTTP_PORT;
    }
}

