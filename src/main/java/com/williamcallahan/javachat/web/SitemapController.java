package com.williamcallahan.javachat.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves a simple sitemap for the primary public routes.
 *
 * <p>Most of the UI is a single-page app, but providing a sitemap helps crawlers discover stable entry points.
 */
@RestController
public class SitemapController {

    private static final int INITIAL_BUFFER_CAPACITY = 512;
    private static final String XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
    private static final String URLSET_OPEN = "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n";
    private static final String URLSET_CLOSE = "</urlset>\n";
    private static final String URL_OPEN = "  <url>\n";
    private static final String URL_CLOSE = "  </url>\n";
    private static final String LOC_OPEN = "    <loc>";
    private static final String LOC_CLOSE = "</loc>\n";
    private static final String LASTMOD_OPEN = "    <lastmod>";
    private static final String LASTMOD_CLOSE = "</lastmod>\n";

    private static final List<String> PUBLIC_ROUTES = List.of(
        "/",
        "/chat",
        "/learn",
        "/guided"
    );

    private final SiteUrlResolver siteUrlResolver;

    /**
     * Creates a sitemap controller.
     *
     * @param siteUrlResolver resolves the public base URL for the request
     */
    public SitemapController(SiteUrlResolver siteUrlResolver) {
        this.siteUrlResolver = siteUrlResolver;
    }

    /**
     * Returns {@code /sitemap.xml}.
     *
     * @param request current HTTP request
     * @return sitemap.xml contents
     */
    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public String sitemap(HttpServletRequest request) {
        String baseUrl = siteUrlResolver.resolvePublicBaseUrl(request);
        String lastModified = LocalDate.now(ZoneOffset.UTC).toString();

        StringBuilder xml = new StringBuilder(INITIAL_BUFFER_CAPACITY);
        xml.append(XML_DECLARATION);
        xml.append(URLSET_OPEN);
        for (String path : PUBLIC_ROUTES) {
            xml.append(URL_OPEN);
            xml.append(LOC_OPEN).append(baseUrl).append(path).append(LOC_CLOSE);
            xml.append(LASTMOD_OPEN).append(lastModified).append(LASTMOD_CLOSE);
            xml.append(URL_CLOSE);
        }
        xml.append(URLSET_CLOSE);
        return xml.toString();
    }
}

