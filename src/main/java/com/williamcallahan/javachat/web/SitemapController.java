package com.williamcallahan.javachat.web;

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import java.io.StringWriter;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves a simple sitemap for the primary public routes.
 *
 * <p>Most of the UI is a single-page app, but providing a sitemap helps crawlers discover stable entry points.
 */
@RestController
@PermitAll
public class SitemapController {

    private static final int INITIAL_BUFFER_CAPACITY = 512;
    private static final String SITEMAP_NAMESPACE = "http://www.sitemaps.org/schemas/sitemap/0.9";

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

        return buildSitemapXml(baseUrl, lastModified);
    }

    private String buildSitemapXml(String baseUrl, String lastModified) {
        XMLOutputFactory factory = XMLOutputFactory.newFactory();
        StringWriter stringWriter = new StringWriter(INITIAL_BUFFER_CAPACITY);
        try {
            XMLStreamWriter xmlWriter = factory.createXMLStreamWriter(stringWriter);
            xmlWriter.writeStartDocument("UTF-8", "1.0");
            xmlWriter.writeStartElement("urlset");
            xmlWriter.writeDefaultNamespace(SITEMAP_NAMESPACE);

            for (String path : PUBLIC_ROUTES) {
                xmlWriter.writeStartElement("url");

                xmlWriter.writeStartElement("loc");
                xmlWriter.writeCharacters(baseUrl + path);
                xmlWriter.writeEndElement();

                xmlWriter.writeStartElement("lastmod");
                xmlWriter.writeCharacters(lastModified);
                xmlWriter.writeEndElement();

                xmlWriter.writeEndElement();
            }

            xmlWriter.writeEndElement();
            xmlWriter.writeEndDocument();
            xmlWriter.close();
        } catch (XMLStreamException streamException) {
            throw new IllegalStateException("Failed to render sitemap XML", streamException);
        }
        return stringWriter.toString();
    }
}
