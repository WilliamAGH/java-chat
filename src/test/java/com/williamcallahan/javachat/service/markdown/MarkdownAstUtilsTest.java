package com.williamcallahan.javachat.service.markdown;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import org.junit.jupiter.api.Test;

/**
 * Verifies citation-marker removal respects the supported numeric marker boundary.
 */
class MarkdownAstUtilsTest {

    @Test
    void stripInlineCitationMarkers_removesThreeDigitsButRetainsFourDigits() {
        Node markdownDocument = Parser.builder().build().parse("Citations [123] and [1234].");

        MarkdownAstUtils.stripInlineCitationMarkers(markdownDocument);

        String renderedHtml = HtmlRenderer.builder().build().render(markdownDocument);
        assertFalse(renderedHtml.contains("[123]"));
        assertTrue(renderedHtml.contains("[1234]"));
    }

    @Test
    void stripInlineCitationMarkers_preservesDefinedNumericReferenceLinks() {
        Node markdownDocument = Parser.builder().build().parse("[123]\n\n[123]: https://example.com");

        MarkdownAstUtils.stripInlineCitationMarkers(markdownDocument);

        String renderedHtml = HtmlRenderer.builder().build().render(markdownDocument);
        assertTrue(renderedHtml.contains("href=\"https://example.com\""));
    }
}
