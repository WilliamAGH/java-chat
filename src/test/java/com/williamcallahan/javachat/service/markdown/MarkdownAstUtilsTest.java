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
        assertFalse(renderedHtml.contains("[123]"), describeAstNodes(markdownDocument));
        assertTrue(renderedHtml.contains("[1234]"));
    }

    private static String describeAstNodes(Node markdownDocument) {
        StringBuilder nodeDescription = new StringBuilder();
        for (Node descendant : markdownDocument.getDescendants()) {
            nodeDescription
                    .append(descendant.getClass().getSimpleName())
                    .append(':')
                    .append(descendant.getChars())
                    .append('\n');
        }
        return nodeDescription.toString();
    }
}
