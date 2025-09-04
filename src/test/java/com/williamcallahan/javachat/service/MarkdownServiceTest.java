package com.williamcallahan.javachat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownServiceTest {
    
    private MarkdownService markdownService;
    
    @BeforeEach
    void setUp() {
        markdownService = new MarkdownService();
    }

    @Test
    @DisplayName("Should insert paragraph breaks for '?' and '!' sentences")
    void testParagraphBreaksQuestionExclamation() {
        String markdown = "Is this correct? Yes! Great.";
        String pre = markdownService.preprocessMarkdown(markdown);
        assertTrue(pre.contains("\n\n"), "Should insert paragraph break after sentences ending with ?/!");
    }

    @Test
    @DisplayName("Should render headers correctly")
    void testHeaders() {
        String markdown = "# Header 1\n## Header 2\n### Header 3";
        String html = markdownService.render(markdown);
        
        assertTrue(html.contains("<h1>Header 1</h1>"), "Should contain H1");
        assertTrue(html.contains("<h2>Header 2</h2>"), "Should contain H2");
        assertTrue(html.contains("<h3>Header 3</h3>"), "Should contain H3");
    }
    
    @Test
    @DisplayName("Should render bold and italic text")
    void testBoldAndItalic() {
        String markdown = "**bold text** and *italic text* and ***bold italic***";
        String html = markdownService.render(markdown);
        
        assertTrue(html.contains("<strong>bold text</strong>"), "Should contain bold");
        assertTrue(html.contains("<em>italic text</em>"), "Should contain italic");
        assertTrue(html.contains("<em><strong>bold italic</strong></em>") || 
                   html.contains("<strong><em>bold italic</em></strong>"), "Should contain bold italic");
    }

    @Test
    @DisplayName("Should normalize spaced bold markers ** text ** -> <strong>text</strong>")
    void testBoldWithSpacesInsideMarkers() {
        String markdown = "This is ** bold ** and also **text**.";
        String html = markdownService.render(markdown);
        assertTrue(html.contains("<strong>bold</strong>"), "Should collapse spaces inside bold markers");
        assertTrue(html.contains("<strong>text</strong>"), "Should still render regular bold");
    }

    @Test
    @DisplayName("Should not split enrichment markers during preprocessing")
    void testEnrichmentNotBrokenByPreprocessing() {
        String markdown = "A sentence. {{hint:This should remain intact even after paragraph logic.}} Next.";
        String html = markdownService.render(markdown);
        assertTrue(html.contains("{{hint:This should remain intact even after paragraph logic.}}"),
                "Enrichment marker should be preserved as a single unit");
    }
    
    @Test
    @DisplayName("Should render unordered lists")
    void testUnorderedLists() {
        String markdown = "- Item 1\n- Item 2\n- Item 3";
        String html = markdownService.render(markdown);
        
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
        String html = markdownService.render(markdown);
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
        String html = markdownService.render(markdown);
        
        assertTrue(html.contains("<pre>"), "Should contain PRE tag");
        assertTrue(html.contains("<code class=\"language-java\">"), "Should contain code with language class");
        assertTrue(html.contains("public class Test {}"), "Should contain code content");
    }
    
    @Test
    @DisplayName("Should render inline code")
    void testInlineCode() {
        String markdown = "Use `System.out.println()` to print";
        String html = markdownService.render(markdown);
        System.out.println("[DEBUG testInlineCode] HTML=\n" + html);
        
        assertTrue(html.contains("<code>System.out.println()</code>"), "Should contain inline code");
    }
    
    @Test
    @DisplayName("Should preserve enrichment markers")
    void testEnrichmentMarkers() {
        String markdown = "Text with {{hint:This is a hint}} and {{warning:This is a warning}}";
        String html = markdownService.render(markdown);
        
        assertTrue(html.contains("{{hint:This is a hint}}"), "Should preserve hint marker");
        assertTrue(html.contains("{{warning:This is a warning}}"), "Should preserve warning marker");
    }
    
    @Test
    @DisplayName("Should handle mixed markdown with enrichments")
    void testMixedContent() {
        String markdown = "# Java 24\n\n**Key features:**\n\n1. Source Version24\n2. Type System\n\n{{hint:Always check the docs}}";
        String html = markdownService.render(markdown);
        
        assertTrue(html.contains("<h1>Java 24</h1>"), "Should have header");
        assertTrue(html.contains("<strong>Key features:</strong>"), "Should have bold text");
        assertTrue(html.contains("<ol>"), "Should have ordered list");
        assertTrue(html.contains("{{hint:Always check the docs}}"), "Should preserve enrichment");
    }
    
    @Test
    @DisplayName("Should handle line breaks properly")
    void testLineBreaks() {
        String markdown = "Line one\nLine two\n\nNew paragraph";
        String html = markdownService.render(markdown);
        
        assertTrue(html.contains("<p>Line one"), "Should have paragraph");
        assertTrue(html.contains("<br />"), "Should have line break");
        assertTrue(html.contains("<p>New paragraph</p>"), "Should have new paragraph");
    }
    
    @Test
    @DisplayName("Should escape raw HTML for security")
    void testHTMLEscaping() {
        String markdown = "<script>alert('XSS')</script>\n\n**Safe bold**";
        String html = markdownService.render(markdown);
        
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
        
        String html = markdownService.render(markdown);
        
        // Check all elements are present
        assertTrue(html.contains("<h1>Main Title</h1>"));
        assertTrue(html.contains("<h2>Features</h2>"));
        assertTrue(html.contains("<h3>Code Example</h3>"));
        assertTrue(html.contains("<ol>"));
        assertTrue(html.contains("<ul>"));
        assertTrue(html.contains("<strong>Source Version24</strong>"));
        assertTrue(html.contains("<code class=\"language-java\">"));
        assertTrue(html.contains("{{background:Java releases often focus on developer experience}}"));
        assertTrue(html.contains("{{hint:Records are immutable by default}}"));
    }

    @Test
    @DisplayName("Should convert inline hyphen bullets after colon into list")
    void testInlineHyphenListAfterColon() {
        String markdown = "Useful in several ways: - Checking divisibility - Extracting digits - Crypto remainders";
        String html = markdownService.render(markdown);
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
        String html = markdownService.render(markdown);
        assertFalse(html.contains("<ul>"), "Minus math should not become a list");
        assertFalse(html.contains("<ol>"), "Minus math should not become a list");
        assertTrue(html.contains("x - y - z"), "Content should be preserved");
    }

    @Test
    @DisplayName("Should fix inline hyphen list in long prose like the remainder operator example")
    void testInlineListFromRemainderExample() {
        String markdown = "The remainder operator is useful in several ways, such as:- Checking divisibility: If x % y equals 0.- Extracting digits: x % 10 gives the rightmost digit.- Its application in encryption algorithms.";
        String html = markdownService.render(markdown);
        assertTrue(html.contains("<ul>"), "Should create unordered list from inline items");
        assertTrue(html.toLowerCase().contains("checking divisibility"));
        assertTrue(html.toLowerCase().contains("extracting digits"));
        assertTrue(html.toLowerCase().contains("encryption"));
    }

    @Test
    @DisplayName("Should convert inline ordered list (1. 2. 3.) into OL")
    void testInlineNumberedList() {
        String markdown = "Key points 1. First 2. Second 3. Third.";
        String html = markdownService.render(markdown);
        System.out.println("[DEBUG testInlineNumberedList] HTML=\n" + html);
        assertTrue(html.contains("<ol>"), "Should create ordered list from inline numbers");
        assertTrue(html.contains("<li>First</li>") || html.contains("<li>First.</li>"));
        assertTrue(html.contains("<li>Second</li>") || html.contains("<li>Second.</li>"));
        assertTrue(html.contains("<li>Third</li>") || html.contains("<li>Third.</li>"));
    }
}
