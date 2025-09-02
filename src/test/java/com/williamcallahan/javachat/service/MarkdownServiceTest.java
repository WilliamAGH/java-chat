package com.williamcallahan.javachat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for MarkdownService to ensure reliable, clean markdown rendering.
 */
class MarkdownServiceTest {
    
    private MarkdownService markdownService;
    
    @BeforeEach
    void setUp() {
        markdownService = new MarkdownService();
    }
    
    @Test
    @DisplayName("Should render basic markdown elements")
    void testBasicMarkdown() {
        String markdown = """
            # Heading 1
            ## Heading 2
            
            This is a **bold** text and this is *italic*.
            
            - List item 1
            - List item 2
              - Nested item
            
            1. Numbered item
            2. Another item
            """;
        
        String html = markdownService.render(markdown);
        
        assertThat(html).contains("<h1>Heading 1</h1>");
        assertThat(html).contains("<h2>Heading 2</h2>");
        assertThat(html).contains("<strong>bold</strong>");
        assertThat(html).contains("<em>italic</em>");
        assertThat(html).contains("<ul>");
        assertThat(html).contains("<ol>");
        assertThat(html).contains("<li>List item 1</li>");
    }
    
    @Test
    @DisplayName("Should render code blocks with language classes")
    void testCodeBlocks() {
        String markdown = """
            ```java
            public class HelloWorld {
                public static void main(String[] args) {
                    System.out.println("Hello, World!");
                }
            }
            ```
            
            Inline `code` example.
            """;
        
        String html = markdownService.render(markdown);
        
        assertThat(html).contains("class=\"language-java\"");
        assertThat(html).contains("<pre>");
        assertThat(html).contains("<code");
        assertThat(html).contains("HelloWorld");
        assertThat(html).contains("<code>code</code>");
    }
    
    @Test
    @DisplayName("Should handle tables correctly")
    void testTables() {
        String markdown = """
            | Column 1 | Column 2 | Column 3 |
            |----------|----------|----------|
            | Data 1   | Data 2   | Data 3   |
            | Data 4   | Data 5   | Data 6   |
            """;
        
        String html = markdownService.render(markdown);
        
        assertThat(html).contains("<table");
        assertThat(html).contains("class=\"markdown-table\"");
        assertThat(html).contains("<thead>");
        assertThat(html).contains("<tbody>");
        assertThat(html).contains("<th>Column 1</th>");
        assertThat(html).contains("<td>Data 1</td>");
    }
    
    @Test
    @DisplayName("Should preserve custom enrichment markers")
    void testCustomEnrichments() {
        String markdown = """
            This is regular text.
            
            {{hint:This is a helpful hint}}
            
            {{warning:This is a warning message}}
            
            {{example:String example = "test";}}
            """;
        
        String html = markdownService.render(markdown);
        
        assertThat(html).contains("{{hint:This is a helpful hint}}");
        assertThat(html).contains("{{warning:This is a warning message}}");
        assertThat(html).contains("{{example:String example = \"test\";}}");
    }
    
    @Test
    @DisplayName("Should escape HTML for security")
    void testHtmlEscaping() {
        String markdown = """
            <script>alert('XSS')</script>
            
            <div onclick="alert('XSS')">Click me</div>
            
            Normal **markdown** text.
            """;
        
        String html = markdownService.render(markdown);
        
        // HTML should be suppressed/escaped
        assertThat(html).doesNotContain("<script>");
        assertThat(html).doesNotContain("onclick=");
        assertThat(html).doesNotContain("alert('XSS')");
        
        // Normal markdown should still work
        assertThat(html).contains("<strong>markdown</strong>");
    }
    
    @Test
    @DisplayName("Should handle line breaks and paragraphs cleanly")
    void testLineBreaksAndParagraphs() {
        String markdown = """
            First paragraph.
            
            Second paragraph with
            a line break within it.
            
            
            
            Third paragraph after multiple blank lines.
            """;
        
        String html = markdownService.render(markdown);
        
        // Should have clean paragraph separation
        assertThat(html).contains("<p>First paragraph.</p>");
        assertThat(html).contains("<p>Second paragraph with");
        assertThat(html).contains("<br />");
        
        // Should not have excessive blank lines
        assertThat(html).doesNotContain("<p></p>");
        assertThat(html).doesNotContain("\n\n\n");
    }
    
    @Test
    @DisplayName("Should handle complex nested structures")
    void testComplexNesting() {
        String markdown = """
            **Bold with `code` inside**
            
            - List with **bold** and *italic*
              - Nested with `code`
                - Double nested
            
            > Blockquote with **bold**
            > and multiple lines
            
            [Link text](https://example.com)
            """;
        
        String html = markdownService.render(markdown);
        
        assertThat(html).contains("<strong>Bold with <code>code</code> inside</strong>");
        assertThat(html).contains("<li>List with <strong>bold</strong>");
        assertThat(html).contains("<blockquote");
        assertThat(html).contains("class=\"markdown-quote\"");
    }
    
    @Test
    @DisplayName("Should handle edge cases gracefully")
    void testEdgeCases() {
        // Empty input
        assertThat(markdownService.render("")).isEmpty();
        assertThat(markdownService.render(null)).isEmpty();
        
        // Unclosed markdown
        String unclosed = "**unclosed bold";
        String html = markdownService.render(unclosed);
        assertThat(html).isNotNull();
        assertThat(html).doesNotContain("<strong>");
        
        // Malformed code block
        String malformed = "```\nunclosed code block";
        html = markdownService.render(malformed);
        assertThat(html).isNotNull();
    }
    
    @Test
    @DisplayName("Should cache rendered markdown")
    void testCaching() {
        String markdown = "# Test Heading\n\nSome content.";
        
        // First render - should miss cache
        String html1 = markdownService.render(markdown);
        
        // Second render - should hit cache
        String html2 = markdownService.render(markdown);
        
        assertThat(html1).isEqualTo(html2);
        
        // Verify cache stats
        var stats = markdownService.getCacheStats();
        assertThat(stats.hitCount()).isGreaterThan(0);
    }
    
    @Test
    @DisplayName("Should handle task lists")
    void testTaskLists() {
        String markdown = """
            - [ ] Unchecked task
            - [x] Checked task
            - [ ] Another unchecked task
            """;
        
        String html = markdownService.render(markdown);
        
        assertThat(html).contains("type=\"checkbox\"");
        assertThat(html).contains("checked");
    }
    
    @Test
    @DisplayName("Should auto-link URLs")
    void testAutoLinks() {
        String markdown = """
            Visit https://example.com for more info.
            
            Email: test@example.com
            """;
        
        String html = markdownService.render(markdown);
        
        assertThat(html).contains("<a href=\"https://example.com\"");
        assertThat(html).contains("<a href=\"mailto:test@example.com\"");
    }
    
    @Test
    @DisplayName("Should handle strikethrough")
    void testStrikethrough() {
        String markdown = "This is ~~strikethrough~~ text.";
        
        String html = markdownService.render(markdown);
        
        assertThat(html).contains("<del>strikethrough</del>");
    }
}