package com.williamcallahan.javachat.web;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.williamcallahan.javachat.config.AppProperties;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller to serve the Single Page Application (SPA) index.html with server-side rendered SEO metadata.
 *
 * <p>This ensures that crawlers and social media bots see the correct title, description, and Open Graph tags
 * for deep links (e.g., /chat, /guided) without executing JavaScript.
 */
@RestController
@PermitAll
@PreAuthorize("permitAll()")
public class SeoController {

    private static final String CLICKY_SCRIPT_URL = "https://static.getclicky.com/js";

    private final Resource indexHtml;
    private final SiteUrlResolver siteUrlResolver;
    private final Map<String, PageMetadata> metadataMap = new ConcurrentHashMap<>();
    private final boolean clickyEnabled;
    private final long clickySiteId;

    // Cache the parsed document to avoid re-reading files, but clone it per request to modify
    private Document cachedIndexDocument;

    /**
     * Creates the SEO controller using the built SPA index.html template and a base URL resolver.
     */
    public SeoController(
            @Value("classpath:/static/index.html") Resource indexHtml,
            SiteUrlResolver siteUrlResolver,
            AppProperties appProperties) {
        this.indexHtml = indexHtml;
        this.siteUrlResolver = siteUrlResolver;
        AppProperties.Clicky clicky =
                Objects.requireNonNull(appProperties, "appProperties").getClicky();
        this.clickyEnabled = clicky.isEnabled();
        this.clickySiteId = clicky.getParsedSiteId();
        initMetadata();
    }

    private void initMetadata() {
        String defaultImage = "/og-image.png";

        PageMetadata base = new PageMetadata(
                "Java Chat - AI-Powered Java Learning With Citations",
                "Learn Java faster with an AI tutor: streaming answers, code examples, and citations to official docs.",
                defaultImage);

        metadataMap.put("/", base);
        metadataMap.put(
                "/chat",
                new PageMetadata(
                        "Java Chat - Streaming Java Tutor With Citations",
                        "Ask Java questions and get streaming answers with citations to official docs and practical examples.",
                        defaultImage));

        PageMetadata guided = new PageMetadata(
                "Guided Java Learning - Java Chat",
                "Structured, step-by-step Java learning paths with examples and explanations.",
                defaultImage);
        metadataMap.put("/guided", guided);
        metadataMap.put("/learn", guided);
    }

    /**
     * Serves the SPA index.html with path-specific SEO metadata for crawlers and social previews.
     */
    @GetMapping(
            value = {"/", "/chat", "/guided", "/learn"},
            produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> serveIndexWithSeo(HttpServletRequest request) {
        try {
            Document doc = getIndexDocument();

            String path = resolvePath(request);
            PageMetadata metadata = metadataMap.getOrDefault(path, metadataMap.get("/"));
            String baseUrl = siteUrlResolver.resolvePublicBaseUrl(request);
            String fullUrl = baseUrl + (path.equals("/") ? "" : path);
            String imageUrl = baseUrl + metadata.imagePath;

            updateDocumentMetadata(doc, metadata, fullUrl, imageUrl);

            return ResponseEntity.ok(doc.html());

        } catch (IOException contentLoadException) {
            return ResponseEntity.internalServerError().body("Error loading content");
        }
    }

    private String resolvePath(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    private void updateDocumentMetadata(Document doc, PageMetadata metadata, String fullUrl, String imageUrl) {
        // Basic Metadata
        doc.title(metadata.title);
        setMeta(doc, "name", "description", metadata.description);

        // Open Graph
        setMeta(doc, "property", "og:title", metadata.title);
        setMeta(doc, "property", "og:description", metadata.description);
        setMeta(doc, "property", "og:url", fullUrl);
        setMeta(doc, "property", "og:image", imageUrl);
        setMeta(doc, "property", "og:image:width", String.valueOf(OpenGraphImageRenderer.OG_IMAGE_WIDTH));
        setMeta(doc, "property", "og:image:height", String.valueOf(OpenGraphImageRenderer.OG_IMAGE_HEIGHT));
        setMeta(doc, "property", "og:image:type", OpenGraphImageRenderer.OG_IMAGE_CONTENT_TYPE);

        // Twitter
        setMeta(doc, "name", "twitter:card", "summary_large_image");
        setMeta(doc, "name", "twitter:title", metadata.title);
        setMeta(doc, "name", "twitter:description", metadata.description);
        setMeta(doc, "name", "twitter:image", imageUrl);

        // Canonical Link
        updateCanonicalLink(doc, fullUrl);

        // Structured Data (JSON-LD)
        updateJsonLd(doc, fullUrl, metadata.description);

        // Analytics
        updateClickyAnalytics(doc);
    }

    private void updateClickyAnalytics(Document doc) {
        Element existingClickyLoader = doc.head().selectFirst("script[src=\"" + CLICKY_SCRIPT_URL + "\"]");
        if (!clickyEnabled) {
            if (existingClickyLoader != null) {
                existingClickyLoader.remove();
            }
            doc.head().select("script").forEach(scriptTag -> {
                String scriptBody = scriptTag.html();
                if (scriptBody != null && scriptBody.contains("clicky_site_ids")) {
                    scriptTag.remove();
                }
            });
            return;
        }

        if (existingClickyLoader != null) {
            return;
        }

        String initializer = "var clicky_site_ids = clicky_site_ids || []; clicky_site_ids.push(" + clickySiteId + ");";
        doc.head().appendElement("script").text(initializer);
        doc.head().appendElement("script").attr("async", "").attr("src", CLICKY_SCRIPT_URL);
    }

    private void updateCanonicalLink(Document doc, String fullUrl) {
        Element canonical = doc.head().selectFirst("link[rel=canonical]");
        if (canonical != null) {
            canonical.attr("href", fullUrl);
        } else {
            doc.head().appendElement("link").attr("rel", "canonical").attr("href", fullUrl);
        }
    }

    private void updateJsonLd(Document doc, String fullUrl, String description) {
        Objects.requireNonNull(fullUrl, "fullUrl must not be null for JSON-LD");
        Objects.requireNonNull(description, "description must not be null for JSON-LD");
        Element jsonLd = doc.getElementById("java-chat-structured-data");
        if (jsonLd != null) {
            String json = """
                {
                  "@context": "https://schema.org",
                  "@type": "WebApplication",
                  "name": "Java Chat",
                  "url": "__FULL_URL__",
                  "applicationCategory": "EducationalApplication",
                  "operatingSystem": "Web",
                  "description": "__DESCRIPTION__"
                }""".replace("__FULL_URL__", escapeJson(fullUrl))
                    .replace("__DESCRIPTION__", escapeJson(description));
            jsonLd.text(json);
        }
    }

    private synchronized Document getIndexDocument() throws IOException {
        if (cachedIndexDocument == null) {
            // Jsoup.parse(File, charset) is better but we have a Resource which might be in a JAR
            cachedIndexDocument = Jsoup.parse(indexHtml.getInputStream(), StandardCharsets.UTF_8.name(), "/");
            cachedIndexDocument.outputSettings().prettyPrint(false);
        }
        return cachedIndexDocument.clone();
    }

    private void setMeta(Document doc, String attrKey, String attrValue, String content) {
        Element meta = doc.head().selectFirst("meta[" + attrKey + "=" + attrValue + "]");
        if (meta != null) {
            meta.attr("content", content);
        } else {
            doc.head().appendElement("meta").attr(attrKey, attrValue).attr("content", content);
        }
    }

    private String escapeJson(String input) {
        Objects.requireNonNull(input, "input must not be null");
        return new String(JsonStringEncoder.getInstance().quoteAsString(input));
    }

    private record PageMetadata(String title, String description, String imagePath) {}
}
