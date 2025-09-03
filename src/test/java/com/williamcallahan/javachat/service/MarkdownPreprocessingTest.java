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
        
        assertTrue(result.contains("\n\n1. boolean"), "Should have paragraph break before list");
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
        
        System.out.println("\nTest: Code block spacing");
        System.out.println("Input: " + input);
        System.out.println("Output: " + result);
        
        assertTrue(result.contains("example:\n\n```"), "Should have paragraph break before code block");
        assertTrue(result.contains("```\nThe result"), "Code block should end properly");
    }
}