package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.config.AppProperties;
import java.util.Objects;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Injects or removes Clicky analytics script tags from server-rendered HTML documents.
 *
 * <p>When Clicky analytics is enabled, this component appends the site-ID initializer
 * and the async script loader to the document {@code <head>}. When disabled, it strips
 * any existing Clicky tags to prevent double-injection from cached templates.
 *
 * <p>Owns all Clicky-specific DOM mutations so that controllers remain free of
 * analytics concerns.
 */
@Component
public class ClickyAnalyticsInjector {

    private static final String CLICKY_SCRIPT_URL = "https://static.getclicky.com/js";
    private static final String CLICKY_INITIALIZER_TEMPLATE =
            "var clicky_site_ids = clicky_site_ids || []; clicky_site_ids.push(%d);";

    private final boolean clickyEnabled;
    private final long clickySiteId;

    /**
     * Reads Clicky configuration from the validated application properties.
     */
    public ClickyAnalyticsInjector(AppProperties appProperties) {
        AppProperties.Clicky clicky =
                Objects.requireNonNull(appProperties, "appProperties").getClicky();
        this.clickyEnabled = clicky.isEnabled();
        this.clickySiteId = clicky.getParsedSiteId();
    }

    /**
     * Applies Clicky analytics to the document: injects tags when enabled, removes them when disabled.
     *
     * @param document the Jsoup document whose {@code <head>} will be modified in place
     */
    public void applyTo(Document document) {
        Element existingClickyLoader = document.head().selectFirst("script[src=\"" + CLICKY_SCRIPT_URL + "\"]");

        if (!clickyEnabled) {
            removeClickyTags(document, existingClickyLoader);
            return;
        }

        if (existingClickyLoader != null) {
            return;
        }

        String initializer = String.format(CLICKY_INITIALIZER_TEMPLATE, clickySiteId);
        document.head().appendElement("script").text(initializer);
        document.head().appendElement("script").attr("async", "").attr("src", CLICKY_SCRIPT_URL);
    }

    private void removeClickyTags(Document document, Element existingLoader) {
        if (existingLoader != null) {
            existingLoader.remove();
        }
        document.head().select("script").forEach(scriptTag -> {
            String scriptBody = scriptTag.html();
            if (scriptBody != null && scriptBody.contains("clicky_site_ids")) {
                scriptTag.remove();
            }
        });
    }
}
