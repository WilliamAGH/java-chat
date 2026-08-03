package com.williamcallahan.javachat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Verifies SEO HTML responses include expected metadata.
 */
@WebMvcTest(controllers = SeoController.class)
@Import({SiteUrlResolver.class, ClickyAnalyticsInjector.class, com.williamcallahan.javachat.config.AppProperties.class})
@TestPropertySource(properties = "app.public-base-url=https://example.com")
@WithMockUser
class SeoControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void serves_root_with_seo_metadata() throws Exception {
        MvcResult mvcOutcome = mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn();
        Document htmlDocument = Jsoup.parse(mvcOutcome.getResponse().getContentAsString());

        assertEquals("JavaChat - AI Learning", htmlDocument.title());
        assertMetaContent(htmlDocument, "property", "og:url", "https://example.com");
        assertMetaContent(htmlDocument, "property", "og:image", "https://example.com/og-image.png");
        assertMetaContent(htmlDocument, "property", "og:image:width", "1200");
        assertMetaContent(htmlDocument, "property", "og:image:height", "630");
        assertMetaContent(htmlDocument, "property", "og:image:type", "image/png");
        assertMetaContent(htmlDocument, "name", "twitter:card", "summary_large_image");
    }

    @Test
    void serves_chat_with_specific_metadata() throws Exception {
        Document htmlDocument = loadSeoDocument("/chat");

        assertEquals("JavaChat - Streaming Java Tutor With Citations", htmlDocument.title());
        assertMetaContent(htmlDocument, "property", "og:url", "https://example.com/chat");
    }

    @Test
    void serves_guided_with_specific_metadata() throws Exception {
        Document htmlDocument = loadSeoDocument("/guided");

        assertEquals("Guided Java Learning - JavaChat", htmlDocument.title());
        assertMetaContent(
                htmlDocument,
                "property",
                "og:description",
                "Guided Java lessons with examples and explanations: Spring Boot, Quarkus, Kotlin and other JVM languages, virtual threads, records, pattern matching, and JUnit testing.");
        assertMetaContent(htmlDocument, "property", "og:url", "https://example.com/guided");
    }

    @Test
    void serves_privacy_with_specific_metadata() throws Exception {
        Document htmlDocument = loadSeoDocument("/privacy");

        assertEquals("Privacy Policy - JavaChat", htmlDocument.title());
        assertMetaContent(
                htmlDocument,
                "property",
                "og:description",
                "How JavaChat collects, uses, discloses, retains, and protects personal information.");
        assertMetaContent(htmlDocument, "property", "og:url", "https://example.com/privacy");
    }

    @Test
    void serves_contact_with_specific_metadata() throws Exception {
        Document htmlDocument = loadSeoDocument("/contact");

        assertEquals("Contact - JavaChat", htmlDocument.title());
        assertMetaContent(
                htmlDocument,
                "property",
                "og:description",
                "Contact the JavaChat team with questions, feedback, or privacy requests.");
        assertMetaContent(htmlDocument, "property", "og:url", "https://example.com/contact");
    }

    /**
     * The deployed CSP blocks inline scripts, so the Clicky site ID must travel on the
     * loader tag's {@code data-id} attribute — never as an inline initializer script.
     */
    @Test
    void injects_clicky_loader_without_inline_initializer() throws Exception {
        Document htmlDocument = loadSeoDocument("/");

        Element clickyLoader = htmlDocument.head().selectFirst("script[src=\"https://static.getclicky.com/js\"]");
        assertNotNull(clickyLoader, "Missing Clicky loader script tag");
        assertEquals("101501246", clickyLoader.attr("data-id"));
        assertTrue(clickyLoader.hasAttr("async"), "Clicky loader must load asynchronously");

        boolean inlineInitializerPresent = htmlDocument.head().select("script").stream()
                .anyMatch(scriptTag -> scriptTag.html().contains("clicky_site_ids"));
        assertFalse(inlineInitializerPresent, "Inline Clicky initializer violates the CSP");
    }

    private Document loadSeoDocument(String path) throws Exception {
        MvcResult mvcOutcome = mvc.perform(get(path)).andExpect(status().isOk()).andReturn();
        return Jsoup.parse(mvcOutcome.getResponse().getContentAsString());
    }

    private void assertMetaContent(
            Document htmlDocument, String attributeName, String attributeMatch, String expectedContent) {
        String attributeSelector = "meta[" + attributeName + "=\"" + attributeMatch + "\"]";
        Element metaElement = htmlDocument.head().selectFirst(attributeSelector);
        assertNotNull(metaElement, "Missing meta tag for " + attributeName + "=" + attributeMatch);
        assertEquals(expectedContent, metaElement.attr("content"));
    }
}
