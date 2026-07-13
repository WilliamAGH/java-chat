package com.williamcallahan.javachat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

        assertEquals("Java Chat - AI-Powered Java Learning With Citations", htmlDocument.title());
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

        assertEquals("Java Chat - Streaming Java Tutor With Citations", htmlDocument.title());
        assertMetaContent(htmlDocument, "property", "og:url", "https://example.com/chat");
    }

    @Test
    void serves_guided_with_specific_metadata() throws Exception {
        Document htmlDocument = loadSeoDocument("/guided");

        assertEquals("Guided Java Learning - Java Chat", htmlDocument.title());
        assertMetaContent(
                htmlDocument,
                "property",
                "og:description",
                "Structured, step-by-step Java learning paths with examples and explanations.");
        assertMetaContent(htmlDocument, "property", "og:url", "https://example.com/guided");
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
