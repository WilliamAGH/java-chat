package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.config.AppProperties;
import java.util.Objects;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Injects or removes the Clicky analytics loader in server-rendered HTML documents.
 *
 * <p>The site ID rides on the loader tag's {@code data-id} attribute instead of an
 * inline initializer script: the deployed CSP restricts {@code script-src} to
 * {@code 'self'} plus allowlisted origins, so any inline script is blocked and would
 * leave Clicky without a site ID. Clicky's loader reads the attribute itself via
 * {@code document.currentScript.getAttribute("data-id")} and pushes it into
 * {@code clicky_site_ids} (verified against https://static.getclicky.com/js).
 *
 * <p>Owns all Clicky-specific DOM mutations so that controllers remain free of
 * analytics concerns.
 */
@Component
public class ClickyAnalyticsInjector {

    private static final String CLICKY_SCRIPT_URL = "https://static.getclicky.com/js";
    private static final String CLICKY_SITE_ID_ATTRIBUTE = "data-id";

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
     * Applies Clicky analytics to the document: ensures a CSP-safe loader tag when enabled,
     * removes all Clicky tags when disabled. Legacy inline {@code clicky_site_ids}
     * initializers are stripped unconditionally because the CSP blocks them either way.
     *
     * @param document the Jsoup document whose {@code <head>} will be modified in place
     */
    public void applyTo(Document document) {
        removeLegacyInitializers(document);
        Element existingClickyLoader = document.head().selectFirst("script[src=\"" + CLICKY_SCRIPT_URL + "\"]");

        if (!clickyEnabled) {
            if (existingClickyLoader != null) {
                existingClickyLoader.remove();
            }
            return;
        }

        if (existingClickyLoader != null) {
            existingClickyLoader.attr(CLICKY_SITE_ID_ATTRIBUTE, Long.toString(clickySiteId));
            return;
        }

        document.head()
                .appendElement("script")
                .attr("async", "")
                .attr(CLICKY_SITE_ID_ATTRIBUTE, Long.toString(clickySiteId))
                .attr("src", CLICKY_SCRIPT_URL);
    }

    private void removeLegacyInitializers(Document document) {
        document.head().select("script").forEach(scriptTag -> {
            if (scriptTag.html().contains("clicky_site_ids")) {
                scriptTag.remove();
            }
        });
    }
}
