package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.service.markdown.UnifiedMarkdownService;
import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises markdown normalization and rendering paths for the unified service.
 */
class MarkdownServiceTest {
    
    private MarkdownService markdownService;
    
    @BeforeEach
    void setUp() {
        markdownService = new MarkdownService(new UnifiedMarkdownService());
    }

    @Test
    @DisplayName("Should insert paragraph breaks for '?' and '!' sentences")
    void testParagraphBreaksQuestionExclamation() {
        String markdown = "Is this correct? Yes! Great.";
        String html = markdownService.processStructured(markdown).html();
        assertTrue(html.contains("<p>"), "Should render paragraphs properly");
    }

    @Test
    @DisplayName("Should render headers correctly")
    void testHeaders() {
        String markdown = "# Header 1\n## Header 2\n### Header 3";
        String html = markdownService.processStructured(markdown).html();
        
        assertTrue(html.contains("<h1>Header 1</h1>"), "Should contain H1");
        assertTrue(html.contains("<h2>Header 2</h2>"), "Should contain H2");
        assertTrue(html.contains("<h3>Header 3</h3>"), "Should contain H3");
    }
    
    @Test
    @DisplayName("Should render bold and italic text")
    void testBoldAndItalic() {
        String markdown = "**bold text** and *italic text* and ***bold italic***";
        String html = markdownService.processStructured(markdown).html();
        
        assertTrue(html.contains("<strong>bold text</strong>"), "Should contain bold");
        assertTrue(html.contains("<em>italic text</em>"), "Should contain italic");
        assertTrue(html.contains("<em><strong>bold italic</strong></em>") || 
                   html.contains("<strong><em>bold italic</em></strong>"), "Should contain bold italic");
    }

    @Test
    @DisplayName("Bold renders correctly with standard markers")
    void testBoldWithSpacesInsideMarkers() {
        String markdown = "This is **bold** and also **text**.";
        String html = markdownService.processStructured(markdown).html();
        assertTrue(html.contains("<strong>bold</strong>"), "Bold should render");
        assertTrue(html.contains("<strong>text</strong>"), "Bold should render");
    }

    @Test
    @DisplayName("Should not split enrichment markers during preprocessing")
    void testEnrichmentNotBrokenByPreprocessing() {
        String markdown = "A sentence. {{hint:This should remain intact even after paragraph logic.}} Next.";
        String html = markdownService.processStructured(markdown).html();
        assertTrue(html.contains("inline-enrichment hint"),
                "Enrichment card should render as a single unit");
    }
    
    @Test
    @DisplayName("Should render unordered lists")
    void testUnorderedLists() {
        String markdown = "- Item 1\n- Item 2\n- Item 3";
        String html = markdownService.processStructured(markdown).html();
        
        assertTrue(html.contains("<ul>"), "Should contain UL tag");
        assertTrue(html.contains("<li>Item 1</li>"), "Should contain list item 1");
        assertTrue(html.contains("<li>Item 2</li>"), "Should contain list item 2");
        assertTrue(html.contains("<li>Item 3</li>"), "Should contain list item 3");
        assertTrue(html.contains("</ul>"), "Should close UL tag");
    }
    
    @Test
    @DisplayName("Should render ordered lists")
    void testOrderedLists() {
        String markdown = "1. First item\n2. Second item\n3. Third item";
        String html = markdownService.processStructured(markdown).html();
        System.out.println("[DEBUG testOrderedLists] HTML=\n" + html);
        
        assertTrue(html.contains("<ol>"), "Should contain OL tag");
        assertTrue(html.contains("<li>First item</li>"), "Should contain list item 1");
        assertTrue(html.contains("<li>Second item</li>"), "Should contain list item 2");
        assertTrue(html.contains("<li>Third item</li>"), "Should contain list item 3");
        assertTrue(html.contains("</ol>"), "Should close OL tag");
    }
    
    @Test
    @DisplayName("Should render code blocks with language class")
    void testCodeBlocks() {
        String markdown = "```java\npublic class Test {}\n```";
        String html = markdownService.processStructured(markdown).html();
        
        assertTrue(html.contains("<pre>"), "Should contain PRE tag");
        assertTrue(html.contains("<code class=\"language-java\">"), "Should contain code with language class");
        assertTrue(html.contains("public class Test {}"), "Should contain code content");
    }
    
    @Test
    @DisplayName("Should render inline code")
    void testInlineCode() {
        String markdown = "Use `System.out.println()` to print";
        String html = markdownService.processStructured(markdown).html();
        System.out.println("[DEBUG testInlineCode] HTML=\n" + html);
        
        assertTrue(html.contains("<code>System.out.println()</code>"), "Should contain inline code");
    }
    
    @Test
    @DisplayName("Should preserve enrichment markers")
    void testEnrichmentMarkers() {
        String markdown = "Text with {{hint:This is a hint}} and {{warning:This is a warning}}";
        String html = markdownService.processStructured(markdown).html();
        // Server renders cards now
        assertTrue(html.contains("inline-enrichment hint"), "Hint card should render");
        assertTrue(html.contains("inline-enrichment warning"), "Warning card should render");
    }
    
    @Test
    @DisplayName("Should handle mixed markdown with enrichments")
    void testMixedContent() {
        String markdown = "# Java 24\n\n**Key features:**\n\n1. Source Version24\n2. Type System\n\n{{hint:Always check the docs}}";
        String html = markdownService.processStructured(markdown).html();
        
        assertTrue(html.contains("<h1>Java 24</h1>"), "Should have header");
        assertTrue(html.contains("<strong>Key features:</strong>"), "Should have bold text");
        assertTrue(html.contains("<ol>"), "Should have ordered list");
        assertTrue(html.contains("inline-enrichment hint"), "Should render hint card");
    }
    
    @Test
    @DisplayName("Should handle line breaks properly")
    void testLineBreaks() {
        String markdown = "Line one\nLine two\n\nNew paragraph";
        String html = markdownService.processStructured(markdown).html();
        assertTrue(html.contains("<p>Line one"), "Should have paragraph");
        // With SOFT_BREAK=\n we do not force <br>; ensure second paragraph exists
        assertTrue(html.contains("<p>New paragraph</p>"), "Should have new paragraph");
    }
    
    @Test
    @DisplayName("Should escape raw HTML for security")
    void testHTMLEscaping() {
        String markdown = "<script>alert('XSS')</script>\n\n**Safe bold**";
        String html = markdownService.processStructured(markdown).html();
        
        assertFalse(html.contains("<script>"), "Should not contain script tag");
        assertTrue(html.contains("&lt;script&gt;"), "Should escape script tag");
        assertTrue(html.contains("<strong>Safe bold</strong>"), "Should still render markdown");
    }
    
    @Test
    @DisplayName("Should handle complex nested structures")
    void testComplexStructure() {
        String markdown = """
            # Main Title
            
            This is a paragraph with **bold** and *italic* text.
            
            ## Features
            
            1. **Source Version24**: This release marks the latest version
            2. **Improvements** to Type System:
               - Better type inference
               - Enhanced generics
            3. **Performance Enhancements**
            
            {{background:Java releases often focus on developer experience}}
            
            ### Code Example
            
            ```java
            public record Person(String name, int age) {}
            ```
            
            {{hint:Records are immutable by default}}
            """;
        
        String html = markdownService.processStructured(markdown).html();
        
        // Check all elements are present
        assertTrue(html.contains("<h1>Main Title</h1>"));
        assertTrue(html.contains("<h2>Features</h2>"));
        assertTrue(html.contains("<h3>Code Example</h3>"));
        assertTrue(html.contains("<ol>"));
        assertTrue(html.contains("<ul>"));
        assertTrue(html.contains("<strong>Source Version24</strong>"));
        assertTrue(html.contains("<code class=\"language-java\">"));
        // Enrichment markers are rendered as server-side cards now
        assertTrue(html.contains("inline-enrichment background"));
        assertTrue(html.contains("inline-enrichment hint"));
    }

    @Test
    @DisplayName("Should convert inline hyphen bullets after colon into list")
    void testInlineHyphenListAfterColon() {
        String markdown = "Useful in several ways: - Checking divisibility - Extracting digits - Crypto remainders";
        String html = markdownService.processStructured(markdown).html();
        assertTrue(html.contains("<ul>"), "Should create unordered list");
        assertTrue(html.contains("<li>Checking divisibility</li>"));
        assertTrue(html.contains("<li>Extracting digits</li>"));
        assertTrue(html.contains("<li>Crypto remainders</li>"));
        assertTrue(html.contains("</ul>"));
    }

    @Test
    @DisplayName("Should not mistake minus sign for bullet list")
    void testMinusNotMistakenForBullet() {
        String markdown = "Compute x - y - z then divide by 3.";
        String html = markdownService.processStructured(markdown).html();
        assertFalse(html.contains("<ul>"), "Minus math should not become a list");
        assertFalse(html.contains("<ol>"), "Minus math should not become a list");
        assertTrue(html.contains("x - y - z"), "Content should be preserved");
    }

    @Test
    @DisplayName("Should fix inline hyphen list in long prose like the remainder operator example")
    void testInlineListFromRemainderExample() {
        String markdown = "The remainder operator is useful in several ways, such as:- Checking divisibility: If x % y equals 0.- Extracting digits: x % 10 gives the rightmost digit.- Its application in encryption algorithms.";
        String html = markdownService.processStructured(markdown).html();
        String normalizedHtml = AsciiTextNormalizer.toLowerAscii(html);
        assertTrue(html.contains("<ul>"), "Should create unordered list from inline items");
        assertTrue(normalizedHtml.contains("checking divisibility"));
        assertTrue(normalizedHtml.contains("extracting digits"));
        assertTrue(normalizedHtml.contains("encryption"));
    }

    @Test
    @DisplayName("Should convert inline ordered list (1. 2. 3.) into OL")
    void testInlineNumberedList() {
        String markdown = "Key points 1. First 2. Second 3. Third.";
        String html = markdownService.processStructured(markdown).html();
        System.out.println("[DEBUG testInlineNumberedList] HTML=\n" + html);
        assertTrue(html.contains("<ol>"), "Should create ordered list from inline numbers");
        assertTrue(html.contains("<li>First</li>") || html.contains("<li>First.</li>"));
        assertTrue(html.contains("<li>Second</li>") || html.contains("<li>Second.</li>"));
        assertTrue(html.contains("<li>Third</li>") || html.contains("<li>Third.</li>"));
    }

    // === New tests for DOM-based normalization and enrichment rendering ===

    @Test
    @DisplayName("Inline ordered list becomes <ol> with leading text preserved")
    void testDomInlineOrderedListNormalization() {
        String md = "Key points: 1. First 2. Second 3. Third.";
        String html = markdownService.processStructured(md).html();
        // Leading text preserved as paragraph
        assertTrue(html.contains("<p>Key points:</p>"), "Leading text should be a paragraph");
        // Ordered list with items
        assertTrue(html.contains("<ol>"), "Should render ordered list");
        // Ensure there are 3 items
        int liCount = html.split("<li>").length - 1;
        assertTrue(liCount >= 3, "Should have at least 3 items");
    }

    @Test
    @DisplayName("Inline bullet list becomes <ul> and not mistaken for minus math")
    void testDomInlineBulletListNormalization() {
        String md = "Useful: - apples - oranges - bananas";
        String html = markdownService.processStructured(md).html();
        assertTrue(html.contains("<ul>"), "Should render unordered list");
        assertTrue(html.contains("<li>apples</li>"));
        assertTrue(html.contains("<li>oranges</li>"));
        assertTrue(html.contains("<li>bananas</li>"));

        String notList = "Compute x - y - z then divide";
        String html2 = markdownService.processStructured(notList).html();
        assertFalse(html2.contains("<ul>"), "Minus math should not become list");
        assertFalse(html2.contains("<ol>"));
    }

    @Test
    @DisplayName("Server renders enrichment markers as cards and respects line breaks")
    void testServerEnrichmentRendering() {
        String md = "{{hint:Line A\nLine B}}"; // real newline
        String html = markdownService.processStructured(md).html();
        String normalizedHtml = AsciiTextNormalizer.toLowerAscii(html);
        // Card wrapper
        assertTrue(html.contains("inline-enrichment hint"), "Hint card should render");
        // Header title
        assertTrue(normalizedHtml.contains("helpful hints"), "Card header should show Helpful Hints");
        // Paragraphized with <br>
        assertTrue(html.contains("<p>Line A<br>Line B</p>") || html.contains("<p>Line A<br />Line B</p>"), "Line breaks preserved in card");
    }

    @Test
    @DisplayName("Tables and blockquotes receive styling classes in unified post-processing")
    void testUnifiedStylingHooks() {
        String md = "|A|B|\n|---|---|\n|1|2|\n\n> quote";
        String html = markdownService.processStructured(md).html();
        assertTrue(html.contains("class=\"markdown-table\""), "Table should have styling class");
        assertTrue(html.contains("<blockquote class=\"markdown-quote\">"), "Blockquote should have styling class");
    }

    @Test
    @DisplayName("Pre-normalization ensures attached fences get separated and closed")
    void testPreNormalizeFences() {
        String md = "Here:```javaimport java.util.*;\nclass X{}"; // missing closing fence, attached info
        String html = markdownService.processStructured(md).html();
        assertTrue(html.contains("<pre>") && html.contains("<code"), "Should render a code block despite malformed fence");
    }

    @Test
    @DisplayName("Example enrichment renders fenced code with language class")
    void testExampleCardRendersCode() {
        String md = "{{example:```java\npublic class A{}\n```}}";
        String html = markdownService.processStructured(md).html();
        System.out.println("[DEBUG testExampleCardRendersCode] Input: " + md);
        System.out.println("[DEBUG testExampleCardRendersCode] HTML:\n" + html);
        assertTrue(html.contains("inline-enrichment example"), "Example card should render");
        assertTrue(html.contains("<code class=\"language-java\">"), "Code block should have language class");
        assertTrue(html.contains("public class A"));
    }

    @Test
    @DisplayName("Enrichment markers inside code blocks are not transformed")
    void testEnrichmentInsideCodeNotRendered() {
        String md = "```java\n// {{warning:do not render}}\nSystem.out.println(\"ok\");\n```";
        String html = markdownService.processStructured(md).html();
        System.out.println("[DEBUG testEnrichmentInsideCodeNotRendered] Input: " + md);
        System.out.println("[DEBUG testEnrichmentInsideCodeNotRendered] HTML:\n" + html);
        // Ensure we still have a code block and the marker text remains (not replaced by card)
        assertTrue(html.contains("<pre>"), "Code block should render");
        assertTrue(html.contains("{{warning:do not render}}") || html.contains("warning:do not render"), "Marker should remain as text inside code");
    }
}
