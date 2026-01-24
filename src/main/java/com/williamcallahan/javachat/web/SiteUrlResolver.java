package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Resolves the public-facing base URL for the current request.
 *
 * <p>This is used for SEO endpoints (robots.txt, sitemap.xml) where absolute URLs are preferred.
 * The public base URL is read from application configuration to avoid reflecting client-controlled
 * host headers into SEO responses.
 */
@Component
public class SiteUrlResolver {

    private final String publicBaseUrl;

    /**
     * Creates the resolver using application configuration.
     *
     * @param appProperties application properties
     */
    public SiteUrlResolver(final AppProperties appProperties) {
        this.publicBaseUrl = appProperties.getPublicBaseUrl();
    }

    /**
     * Resolves the request's public base URL (scheme + host [+ port]).
     *
     * @param request current HTTP request
     * @return base URL like {@code https://example.com}
     */
    public String resolvePublicBaseUrl(HttpServletRequest request) {
        return publicBaseUrl;
    }
}
