package com.williamcallahan.javachat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownPreprocessingTest {
    
    private MarkdownService markdownService;
    
    @BeforeEach
    void setUp() {
        markdownService = new MarkdownService();
    }
    
    @Test
    void testColonDashListPattern() {
        String input = "The remainder operator has several uses, such as:- Checking divisibility- Extracting digits";
        String result = markdownService.preprocessMarkdown(input);
        
        System.out.println("Test: Colon-dash list pattern");
        System.out.println("Input: " + input);
        System.out.println("Output: " + result);
        
        assertTrue(result.contains("\n\n- Checking"), "Should have paragraph break before first list item");
        assertTrue(result.contains("\n- Extracting"), "Should have line break before second list item");
    }
    
    @Test
    void testInlineNumberedList() {
        String input = "The primitive types are:1. boolean: true or false. 2. byte: 8-bit signed.";
        String result = markdownService.preprocessMarkdown(input);
        
        System.out.println("\nTest: Inline numbered list");
        System.out.println("Input: " + input);
        System.out.println("Output: " + result);
        System.out.println("Result bytes: " + java.util.Arrays.toString(result.getBytes()));
        
        // The list should be separated from the text - check for any newline separation
        boolean hasSeparation = result.contains(":\n1.") || result.contains(":\n\n1.");
        assertTrue(hasSeparation, "Should have newline separation before list");
        assertTrue(result.contains("\n2. byte"), "Should have line break before item 2");
    }
    
    @Test
    void testMissingSpacesAfterPunctuation() {
        String input = "This is a sentence.Here is another!And a third?Yet another.";
        String result = markdownService.preprocessMarkdown(input);
        
        System.out.println("\nTest: Missing spaces after punctuation");
        System.out.println("Input: " + input);
        System.out.println("Output: " + result);
        
        // Spaces are added, and paragraph breaks may be added too for readability
        assertTrue(result.contains(". Here"), "Should add space after period");
        // After 2 sentences, paragraph break is expected, so check for either space or paragraph break
        assertTrue(result.contains("! And") || result.contains("!\n\nAnd") || result.contains("! \n\nAnd"), 
                  "Should add space or paragraph break after exclamation");
        assertTrue(result.contains("? Yet"), "Should add space after question mark");
    }
    
    @Test
    void testParagraphBreaksInLongText() {
        String input = "The % operator in Java is the remainder operator. It returns the remainder after division. For example, 10 % 3 equals 1. This is useful for checking divisibility. When a % b equals 0, a is divisible by b.";
        String result = markdownService.preprocessMarkdown(input);
        
        System.out.println("\nTest: Paragraph breaks in long text");
        System.out.println("Input: " + input);
        System.out.println("Output: " + result);
        
        assertTrue(result.contains("\n\n"), "Should contain paragraph breaks");
        int paragraphs = result.split("\n\n").length;
        assertTrue(paragraphs > 1, "Should have multiple paragraphs, got: " + paragraphs);
    }
    
    @Test
    void testCodeBlockSpacing() {
        String input = "Here's an example:```java\nint x = 10 % 3;\n```The result is 1.";
        String result = markdownService.preprocessMarkdown(input);
        String html = markdownService.render(input);
        
        System.out.println("\nTest: Code block spacing");
        System.out.println("Input: " + input);
        System.out.println("Output: " + result);
        
        // Either preprocessor adds paragraph break OR final HTML renders code as its own block
        boolean preSeparated = result.contains("example:\n\n```");
        boolean htmlHasPre = html.contains("<pre>");
        boolean htmlHasLang = html.contains("<code class=\"language-");
        boolean htmlSeparated = htmlHasPre || html.contains("</p>\n\n<pre>") || html.contains("<pre><code class=\"language-java\">");
        assertTrue(preSeparated || htmlSeparated, "Code block should be separated as a block (pre or final HTML)");
        assertTrue(htmlHasPre || htmlHasLang, "Final HTML must render fenced code as a block or with language class");
    }
    
    @Test
    void testColonDirectlyBeforeCodeFence() {
        // This is the exact issue from the screenshot
        String input = "with a flexible constructor approach:```java\nimport java.util.Scanner;";
        String result = markdownService.preprocessMarkdown(input);
        String html = markdownService.render(input);
        
        System.out.println("\nTest: Colon directly before code fence");
        System.out.println("Input: " + input);
        System.out.println("Preprocessed: " + result);
        System.out.println("HTML contains <pre>: " + html.contains("<pre>"));
        
        // The preprocessor should add paragraph break after colon
        assertTrue(result.contains("approach:\n\n```"), "Should have paragraph break between colon and fence");
        
        // The HTML should properly render as a code block
        assertTrue(html.contains("<pre>"), "HTML should contain <pre> tag");
        assertTrue(html.contains("<code"), "HTML should contain <code> tag");
        assertFalse(html.contains("approach:```"), "HTML should not have colon directly attached to fence");
    }
    
    @Test
    void testPeriodDirectlyBeforeCodeFence() {
        String input = "Here is the code.```python\nprint('hello')";
        String result = markdownService.preprocessMarkdown(input);

        System.out.println("\nTest: Period directly before code fence");
        System.out.println("Input: " + input);
        System.out.println("Output: " + result);

        assertTrue(result.contains("code.\n\n```"), "Should have paragraph break between period and fence");
    }

    @Test
    void testJavaCodeBlockWithComplexLanguageTag() {
        String input = "Here's a Java example:```java\npublic class Hello {\n    public static void main(String[] args) {\n        System.out.println(\"Hello, World!\");\n    }\n}\n```";
        String result = markdownService.preprocessMarkdown(input);
        String html = markdownService.render(input);

        assertTrue(result.contains("example:\n\n```java"), "Should have paragraph break before Java code fence");
        assertTrue(html.contains("<pre>"), "HTML should contain <pre> tag");
        assertTrue(html.contains("<code class=\"language-java\">"), "Should contain code with Java language class");
        assertTrue(html.contains("public class Hello"), "Should contain Java code content");
    }

    @Test
    void testMultipleJavaCodeBlocks() {
        String input = "First example:```java\nSystem.out.println(\"First\");\n```\n\nSecond example:```java\nSystem.out.println(\"Second\");\n```";
        String html = markdownService.render(input);

        System.out.println("\nTest: Multiple Java code blocks");
        System.out.println("Input: " + input);

        assertTrue(html.contains("<code class=\"language-java\">"), "Should contain Java language class");
        assertTrue(html.contains("First"), "Should contain first code block content");
        assertTrue(html.contains("Second"), "Should contain second code block content");
        // Count occurrences of <pre> tags
        int preCount = html.split("<pre>").length - 1;
        assertTrue(preCount >= 2, "Should have at least 2 <pre> tags for 2 code blocks");
    }

    @Test
    void testJavaCodeBlockAfterColon() {
        String input = "The solution is:```java\npublic static void main(String[] args) {\n    // Java code here\n}\n```";
        String result = markdownService.preprocessMarkdown(input);
        String html = markdownService.render(input);

        System.out.println("\nTest: Java code block after colon");
        System.out.println("Input: " + input);
        System.out.println("Preprocessed: " + result);

        assertTrue(result.contains("is:\n\n```java"), "Should have paragraph break after colon");
        assertTrue(html.contains("<pre>"), "HTML should contain <pre> tag");
        assertTrue(html.contains("<code class=\"language-java\">"), "Should contain Java language class");
        assertTrue(html.contains("public static void main"), "Should contain Java method");
    }

    @Test
    void testJavaCodeBlockWithSpecialCharacters() {
        String input = "Advanced Java features:```java\n// Using generics and lambdas\nList<String> names = Arrays.asList(\"Alice\", \"Bob\");\nnames.stream().filter(name -> name.length() > 3).forEach(System.out::println);\n```";
        String html = markdownService.render(input);

        System.out.println("\nTest: Java code block with special characters");
        System.out.println("Input: " + input);

        assertTrue(html.contains("<pre>"), "HTML should contain <pre> tag");
        assertTrue(html.contains("<code class=\"language-java\">"), "Should contain Java language class");
        assertTrue(html.contains("List&lt;String&gt;"), "Should properly escape generics");
        assertTrue(html.contains("System.out::println"), "Should contain method reference");
    }

    @Test
    void testEmptyJavaCodeBlock() {
        String input = "Empty code block:```java\n```";
        String html = markdownService.render(input);

        System.out.println("\nTest: Empty Java code block");
        System.out.println("Input: " + input);

        assertTrue(html.contains("<pre>"), "HTML should contain <pre> tag");
        assertTrue(html.contains("<code class=\"language-java\">"), "Should contain Java language class");
        // Should handle empty blocks gracefully without breaking
    }

    @Test
    void testJavaCodeBlockWithAnnotations() {
        String input = "Spring Boot example:```java\n@RestController\npublic class UserController {\n    @GetMapping(\"/users\")\n    public List<User> getUsers() {\n        return userService.findAll();\n    }\n}\n```";
        String html = markdownService.render(input);

        System.out.println("\nTest: Java code block with annotations");
        System.out.println("Input: " + input);

        assertTrue(html.contains("<pre>"), "HTML should contain <pre> tag");
        assertTrue(html.contains("<code class=\"language-java\">"), "Should contain Java language class");
        assertTrue(html.contains("@RestController"), "Should contain Spring annotation");
        assertTrue(html.contains("@GetMapping"), "Should contain HTTP annotation");
    }
}
