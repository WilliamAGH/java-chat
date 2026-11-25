package com.williamcallahan.javachat.service.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.junit.jupiter.api.Test;

/**
 * End-to-end regression coverage for nested inline-list prose preservation.
 *
 * <p>Exercises the full {@link UnifiedMarkdownService#process(String)} pipeline (Flexmark →
 * {@code renderInlineLists} → {@link InlineListParser#tryConvert(String)}), which previously dropped
 * prose that sits between a colon and the first nested list marker, and/or after the last nested
 * list marker, when an inline list item contained a colon-nested sub-list.
 */
class InlineListNestedTextDropReproTest {

    private final UnifiedMarkdownService service = new UnifiedMarkdownService();

    @Test
    void process_nestedLeadingProseBetweenColonAndMarker_isPreservedAsSiblingParagraph() {
        String markdown = "Key points: 1. Setup phase: First gather tools a. Knife b. Spoon 2. Final cleanup";

        String html = service.process(markdown).html();

        Document document = parseFragment(html);
        // The nested leading prose "First gather tools" must survive rendering as a <p>.
        int proseIndex = indexOfParagraphContaining(document, "First gather tools");
        assertNotEquals(-1, proseIndex, "nested leading prose must be rendered, html=" + html);
        // The nested list items also remain intact.
        int nestedListIndex = indexOfFirstListContaining(document, "Knife");
        assertNotEquals(-1, nestedListIndex, "nested list must be rendered, html=" + html);
        // Ordering: the leading prose paragraph precedes the nested list containing "Knife".
        assertTrue(proseIndex < nestedListIndex, "nested leading prose must precede its nested list, html=" + html);
    }

    @Test
    void process_nestedTrailingProseAfterNestedList_isPreservedAsSiblingParagraph() {
        String markdown = "Steps: 1. Setup: Prepare phase a. Step A b. Step B. Then finish 2. Done";

        String html = service.process(markdown).html();

        Document document = parseFragment(html);
        // Both the nested leading prose and the nested trailing prose survive.
        int leadingProseIndex = indexOfParagraphContaining(document, "Prepare phase");
        int trailingProseIndex = indexOfParagraphContaining(document, "Then finish");
        assertNotEquals(-1, leadingProseIndex, "nested leading prose must be rendered, html=" + html);
        assertNotEquals(-1, trailingProseIndex, "nested trailing prose must be rendered, html=" + html);
        int nestedListIndex = indexOfFirstListContaining(document, "Step A");
        assertNotEquals(-1, nestedListIndex, "nested list must be rendered, html=" + html);
        // Ordering: leading prose → nested list → trailing prose.
        assertTrue(
                leadingProseIndex < nestedListIndex, "nested leading prose must precede its nested list, html=" + html);
        assertTrue(
                nestedListIndex < trailingProseIndex,
                "nested trailing prose must follow its nested list, html=" + html);
    }

    @Test
    void process_nestedListWithoutExtraProse_introducesNoSpuriousParagraphs() {
        // The originally-supported colon-abutting shape must keep rendering identically, with no
        // spurious prose paragraphs injected alongside the nested list.
        String markdown = "Notes: 1. Parent: a. Child one b. Child two 2. Another";

        String html = service.process(markdown).html();

        Document document = parseFragment(html);
        Element nestedList = findFirstListWithItems(document, "Child one", "Child two");
        assertNotNull(nestedList, html);
        assertEquals(2, nestedList.children().size(), html);
        // Only the top-level leading "<p>Notes:</p>" should exist; no nested prose paragraphs.
        assertEquals(1, document.select("p").size(), "no spurious prose paragraphs, html=" + html);
        assertEquals("Notes:", document.select("p").first().text(), html);
    }

    private static Document parseFragment(String html) {
        Document document = Jsoup.parseBodyFragment(html);
        document.outputSettings().prettyPrint(false);
        return document;
    }

    private static int indexOfParagraphContaining(Document document, String text) {
        Elements bodyChildren = document.body().children();
        for (int index = 0; index < bodyChildren.size(); index++) {
            Element bodyChild = bodyChildren.get(index);
            if (bodyChild.tagName().equals("p") && bodyChild.text().contains(text)) {
                return index;
            }
        }
        return -1;
    }

    private static int indexOfFirstListContaining(Document document, String itemText) {
        Elements bodyChildren = document.body().children();
        for (int index = 0; index < bodyChildren.size(); index++) {
            Element bodyChild = bodyChildren.get(index);
            if (isListElement(bodyChild) && listContainsItemText(bodyChild, itemText)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isListElement(Element element) {
        return element.tagName().equals("ol") || element.tagName().equals("ul");
    }

    private static boolean listContainsItemText(Element listElement, String itemText) {
        for (Element listItem : listElement.children()) {
            if (listItem.text().contains(itemText)) {
                return true;
            }
        }
        return false;
    }

    private static Element findFirstListWithItems(Document document, String... itemTexts) {
        for (Element listElement : document.select("ol, ul")) {
            boolean allPresent = true;
            for (String expected : itemTexts) {
                if (!listContainsExactItem(listElement, expected)) {
                    allPresent = false;
                    break;
                }
            }
            if (allPresent) {
                return listElement;
            }
        }
        return null;
    }

    private static boolean listContainsExactItem(Element listElement, String expected) {
        for (Element listItem : listElement.children()) {
            if (listItem.text().equals(expected)) {
                return true;
            }
        }
        return false;
    }
}
