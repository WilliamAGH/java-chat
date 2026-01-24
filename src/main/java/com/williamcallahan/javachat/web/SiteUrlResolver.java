package com.williamcallahan.javachat.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Resolves the public-facing base URL for the current request.
 *
 * <p>This is used for SEO endpoints (robots.txt, sitemap.xml) where absolute URLs are preferred.
 * It relies on servlet request values, which can already include proxy adjustments when the
 * container is configured to process forwarded headers.
 */
@Component
public class SiteUrlResolver {

    private static final String SCHEME_HTTPS = "https";
    private static final int DEFAULT_HTTPS_PORT = 443;
    private static final int DEFAULT_HTTP_PORT = 80;
    private static final String SCHEME_SEPARATOR = "://";
    private static final char PORT_SEPARATOR = ':';

    /**
     * Resolves the request's public base URL (scheme + host [+ port]).
     *
     * @param request current HTTP request
     * @return base URL like {@code https://example.com}
     */
    public String resolvePublicBaseUrl(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        boolean defaultPort = isDefaultPort(scheme, port);
        String authority = defaultPort ? host : host + PORT_SEPARATOR + port;
        return scheme + SCHEME_SEPARATOR + authority;
    }

    private static boolean isDefaultPort(String scheme, int port) {
        String normalizedScheme = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
        if (SCHEME_HTTPS.equals(normalizedScheme)) {
            return port == DEFAULT_HTTPS_PORT;
        }
        return port == DEFAULT_HTTP_PORT;
    }
}
