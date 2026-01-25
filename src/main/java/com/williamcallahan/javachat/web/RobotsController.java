package com.williamcallahan.javachat.web;

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves a robots.txt that points crawlers to {@code /sitemap.xml} and avoids indexing API endpoints.
 */
@RestController
@PermitAll
@PreAuthorize("permitAll()")
public class RobotsController {

    private static final int INITIAL_BUFFER_CAPACITY = 256;
    private static final String ROBOTS_USER_AGENT = "User-agent: *\n";
    private static final String ROBOTS_ALLOW_ROOT = "Allow: /\n";
    private static final String ROBOTS_DISALLOW_API = "Disallow: /api/\n";
    private static final String ROBOTS_DISALLOW_ACTUATOR = "Disallow: /actuator/\n";
    private static final String ROBOTS_SITEMAP_PREFIX = "Sitemap: ";
    private static final String SITEMAP_PATH = "/sitemap.xml";

    private final SiteUrlResolver siteUrlResolver;

    /**
     * Creates a robots controller.
     *
     * @param siteUrlResolver resolves the public base URL for the request
     */
    public RobotsController(SiteUrlResolver siteUrlResolver) {
        this.siteUrlResolver = siteUrlResolver;
    }

    /**
     * Returns {@code /robots.txt}.
     *
     * @param request current HTTP request
     * @return robots.txt contents
     */
    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public String robots(HttpServletRequest request) {
        String baseUrl = siteUrlResolver.resolvePublicBaseUrl(request);
        String sitemapUrl = baseUrl + SITEMAP_PATH;

        StringBuilder robotsTxt = new StringBuilder(INITIAL_BUFFER_CAPACITY);
        robotsTxt.append(ROBOTS_USER_AGENT);
        robotsTxt.append(ROBOTS_ALLOW_ROOT);
        robotsTxt.append(ROBOTS_DISALLOW_API);
        robotsTxt.append(ROBOTS_DISALLOW_ACTUATOR);
        robotsTxt.append(ROBOTS_SITEMAP_PREFIX).append(sitemapUrl).append('\n');
        return robotsTxt.toString();
    }
}
