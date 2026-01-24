package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.service.markdown.UnifiedMarkdownService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies preprocessing rules applied before markdown rendering.
 */
class MarkdownPreprocessingTest {
    
    private MarkdownService markdownService;
    
    @BeforeEach
    void setUp() {
        markdownService = new MarkdownService(new UnifiedMarkdownService());
    }
    
    @Test
    void testColonDashListPattern() {
        String input = "The remainder operator has several uses, such as:- Checking divisibility- Extracting digits";
        String html = markdownService.processStructured(input).html();

        System.out.println("Test: Colon-dash list pattern");
        System.out.println("Input: " + input);
        System.out.println("HTML: " + html);

        assertTrue(html.contains("<ul>"), "Should render as unordered list");
        assertTrue(html.contains("<li>Checking divisibility</li>"), "Should contain first list item");
        assertTrue(html.contains("<li>Extracting digits</li>"), "Should contain second list item");
    }
    
    @Test
    void testInlineNumberedList() {
        String input = "The primitive types are:1. boolean: true or false. 2. byte: 8-bit signed.";
        String html = markdownService.processStructured(input).html();

        System.out.println("\nTest: Inline numbered list");
        System.out.println("Input: " + input);
        System.out.println("HTML: " + html);

        // Should render as ordered list
        assertTrue(html.contains("<ol>"), "Should render as ordered list");
        assertTrue(html.contains("<li>boolean"), "Should contain first list item");
        assertTrue(html.contains("<li>byte"), "Should contain second list item");
    }
    
    @Test
    void testMissingSpacesAfterPunctuation() {
        String input = "This is a sentence.Here is another!And a third?Yet another.";
        String html = markdownService.processStructured(input).html();

        System.out.println("\nTest: Missing spaces after punctuation");
        System.out.println("Input: " + input);
        System.out.println("HTML: " + html);

        // Should render as paragraphs with proper spacing
        assertTrue(html.contains("<p>"), "Should render as paragraphs");
        assertTrue(html.contains("sentence. Here"), "Should add space after period");
        assertTrue(html.contains("another! And") || html.contains("another!</p>"), "Should handle exclamation");
        assertTrue(html.contains("third? Yet"), "Should add space after question mark");
    }
    
    @Test
    void testParagraphBreaksInLongText() {
        String input = "The % operator in Java is the remainder operator. It returns the remainder after division. For example, 10 % 3 equals 1. This is useful for checking divisibility. When a % b equals 0, a is divisible by b.";
        String html = markdownService.processStructured(input).html();

        System.out.println("\nTest: Paragraph breaks in long text");
        System.out.println("Input: " + input);
        System.out.println("HTML: " + html);

        assertTrue(html.contains("<p>"), "Should render as paragraphs");
        // Count paragraph tags
        int paraCount = html.split("<p>").length - 1;
        assertTrue(paraCount > 1, "Should have multiple paragraphs, got: " + paraCount);
    }
    
    @Test
    void testCodeBlockSpacing() {
        String input = "Here's an example:```java\nint x = 10 % 3;\n```The result is 1.";
        String html = markdownService.processStructured(input).html();

        System.out.println("\nTest: Code block spacing");
        System.out.println("Input: " + input);
        System.out.println("HTML: " + html);

        // Should render code block properly
        assertTrue(html.contains("<pre>"), "Should contain pre tag");
        assertTrue(html.contains("<code class=\"language-java\">"), "Should contain code with language class");
        assertTrue(html.contains("int x = 10 % 3"), "Should contain code content");
    }
    
    @Test
    void testClosingFenceSeparatesProse() {
        String input = "Here's an example:```java\nint x = 10 % 3;\n```The result is 1.";
        String html = markdownService.processStructured(input).html();

        // The prose after the closing fence must be outside the code block
        assertTrue(html.contains("<pre>"), "Should contain code block");
        assertTrue(html.contains("</code></pre>"), "Should close code block");
        assertFalse(html.contains("```The"), "Closing fence must be on its own line, not inside code");
        int codeClose = html.indexOf("</code></pre>");
        int theIdx = html.indexOf("The", codeClose + 1);
        int restIdx = html.indexOf("result is 1.", codeClose + 1);
        assertTrue(theIdx > codeClose && restIdx > codeClose, "Prose must appear after the closed code block");
    }
    
    @Test
    void testColonDirectlyBeforeCodeFence() {
        // This is the exact issue from the screenshot
        String input = "with a flexible constructor approach:```java\nimport java.util.Scanner;";
        String html = markdownService.processStructured(input).html();

        System.out.println("\nTest: Colon directly before code fence");
        System.out.println("Input: " + input);
        System.out.println("HTML contains <pre>: " + html.contains("<pre>"));

        // The HTML should properly render as a code block
        assertTrue(html.contains("<pre>"), "HTML should contain <pre> tag");
        assertTrue(html.contains("<code"), "HTML should contain <code> tag");
        assertTrue(html.contains("import java.util.Scanner"), "Should contain code content");
    }
    
    @Test
    void testPeriodDirectlyBeforeCodeFence() {
        String input = "Here is the code.```python\nprint('hello')";
        String html = markdownService.processStructured(input).html();

        System.out.println("\nTest: Period directly before code fence");
        System.out.println("Input: " + input);
        System.out.println("HTML: " + html);

        assertTrue(html.contains("<pre>"), "Should render code block");
        assertTrue(html.contains("<code class=\"language-python\">"), "Should contain Python language class");
        assertTrue(html.contains("print('hello')"), "Should contain code content");
    }

    @Test
    void testJavaCodeBlockWithComplexLanguageTag() {
        String input = "Here's a Java example:```java\npublic class Hello {\n    public static void main(String[] args) {\n        System.out.println(\"Hello, World!\");\n    }\n}\n```";
        String html = markdownService.processStructured(input).html();

        assertTrue(html.contains("<pre>"), "HTML should contain <pre> tag");
        assertTrue(html.contains("<code class=\"language-java\">"), "Should contain code with Java language class");
        assertTrue(html.contains("public class Hello"), "Should contain Java code content");
    }

    @Test
    void testMultipleJavaCodeBlocks() {
        String input = "First example:```java\nSystem.out.println(\"First\");\n```\n\nSecond example:```java\nSystem.out.println(\"Second\");\n```";
        String html = markdownService.processStructured(input).html();

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
        String html = markdownService.processStructured(input).html();

        System.out.println("\nTest: Java code block after colon");
        System.out.println("Input: " + input);
        System.out.println("HTML: " + html);

        assertTrue(html.contains("<pre>"), "HTML should contain <pre> tag");
        assertTrue(html.contains("<code class=\"language-java\">"), "Should contain Java language class");
        assertTrue(html.contains("public static void main"), "Should contain Java method");
    }

    @Test
    void testJavaCodeBlockWithSpecialCharacters() {
        String input = "Advanced Java features:```java\n// Using generics and lambdas\nList<String> names = Arrays.asList(\"Alice\", \"Bob\");\nnames.stream().filter(name -> name.length() > 3).forEach(System.out::println);\n```";
        String html = markdownService.processStructured(input).html();

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
        String html = markdownService.processStructured(input).html();

        System.out.println("\nTest: Empty Java code block");
        System.out.println("Input: " + input);

        assertTrue(html.contains("<pre>"), "HTML should contain <pre> tag");
        assertTrue(html.contains("<code class=\"language-java\">"), "Should contain Java language class");
        // Should handle empty blocks gracefully without breaking
    }

    @Test
    void testJavaCodeBlockWithAnnotations() {
        String input = "Spring Boot example:```java\n@RestController\npublic class UserController {\n    @GetMapping(\"/users\")\n    public List<User> getUsers() {\n        return userService.findAll();\n    }\n}\n```";
        String html = markdownService.processStructured(input).html();

        System.out.println("\nTest: Java code block with annotations");
        System.out.println("Input: " + input);

        assertTrue(html.contains("<pre>"), "HTML should contain <pre> tag");
        assertTrue(html.contains("<code class=\"language-java\">"), "Should contain Java language class");
        assertTrue(html.contains("@RestController"), "Should contain Spring annotation");
        assertTrue(html.contains("@GetMapping"), "Should contain HTTP annotation");
    }
}
