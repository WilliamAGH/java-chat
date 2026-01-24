package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.service.markdown.UnifiedMarkdownService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers list formatting scenarios for markdown processing.
 */
class ComprehensiveListFormattingTest {
    
    private MarkdownService markdownService;
    
    @BeforeEach
    void setUp() {
        markdownService = new MarkdownService(new UnifiedMarkdownService());
    }
    
    @Test
    void testNumberedListWithPeriod() {
        String input = "The types are:1. boolean 2. byte 3. int 4. long";
        String html = markdownService.processStructured(input).html();

        System.out.println("Test: Numbered list with periods");
        System.out.println("Input: " + input);
        System.out.println("HTML: " + html);

        assertTrue(html.contains("<ol>"), "Should render as ordered list");
        assertTrue(html.contains("<li>boolean</li>"), "Should contain first item");
        assertTrue(html.contains("<li>byte</li>"), "Should contain second item");
        assertTrue(html.contains("<li>int</li>"), "Should contain third item");
        assertTrue(html.contains("<li>long</li>"), "Should contain fourth item");
    }
    
    @Test
    void testNumberedListWithParenthesis() {
        String input = "The steps include:1) Setup 2) Configure 3) Deploy";
        String html = markdownService.processStructured(input).html();

        System.out.println("\nTest: Numbered list with parenthesis");
        System.out.println("Input: " + input);
        System.out.println("HTML: " + html);

        assertTrue(html.contains("<ol>"), "Should render as ordered list");
        assertTrue(html.contains("<li>Setup</li>"), "Should contain first item");
        assertTrue(html.contains("<li>Configure</li>"), "Should contain second item");
        assertTrue(html.contains("<li>Deploy</li>"), "Should contain third item");
    }
    
    @Test
    void testRomanNumeralsLowercase() {
        String input = "The stages are:i. Planning ii. Development iii. Testing";
        String html = markdownService.processStructured(input).html();

        System.out.println("\nTest: Roman numerals (lowercase)");
        System.out.println("Input: " + input);
        System.out.println("HTML: " + html);

        assertTrue(html.contains("<ol>"), "Should render as ordered list");
        assertTrue(html.contains("<li>Planning</li>"), "Should contain first item");
        assertTrue(html.contains("<li>Development</li>"), "Should contain second item");
        assertTrue(html.contains("<li>Testing</li>"), "Should contain third item");
    }

    @Test
    void testLetterListLowercase() {
        String input = "Options include:a. First option b. Second option c. Third option";
        String html = markdownService.processStructured(input).html();

        System.out.println("\nTest: Letter list (lowercase)");
        System.out.println("Input: " + input);
        System.out.println("HTML: " + html);

        assertTrue(html.contains("<ol>"), "Should render as ordered list");
        assertTrue(html.contains("<li>First option</li>"), "Should contain first item");
        assertTrue(html.contains("<li>Second option</li>"), "Should contain second item");
        assertTrue(html.contains("<li>Third option</li>"), "Should contain third item");
    }

    @Test
    void testDashBulletList() {
        String input = "Features:- Fast processing- High accuracy- Low latency";
        String html = markdownService.processStructured(input).html();

        System.out.println("\nTest: Dash bullet list");
        System.out.println("Input: " + input);
        System.out.println("HTML: " + html);

        assertTrue(html.contains("<ul>"), "Should render as unordered list");
        assertTrue(html.contains("<li>Fast processing</li>"), "Should contain first item");
        assertTrue(html.contains("<li>High accuracy</li>"), "Should contain second item");
        assertTrue(html.contains("<li>Low latency</li>"), "Should contain third item");
    }
    
    @Test
    void testAsteriskBulletList() {
        String input = "Benefits are:* Cost effective* Time saving* Easy to use";
        String html = markdownService.processStructured(input).html();

        System.out.println("\nTest: Asterisk bullet list");
        System.out.println("Input: " + input);
        System.out.println("HTML: " + html);

        assertTrue(html.contains("<ul>"), "Should render as unordered list");
        assertTrue(html.contains("<li>Cost effective</li>"), "Should contain first item");
        assertTrue(html.contains("<li>Time saving</li>"), "Should contain second item");
        assertTrue(html.contains("<li>Easy to use</li>"), "Should contain third item");
    }

    @Test
    void testPlusBulletList() {
        String input = "Advantages:+ Scalable+ Reliable+ Secure";
        String html = markdownService.processStructured(input).html();

        System.out.println("\nTest: Plus bullet list");
        System.out.println("Input: " + input);
        System.out.println("HTML: " + html);

        assertTrue(html.contains("<ul>"), "Should render as unordered list");
        assertTrue(html.contains("<li>Scalable</li>"), "Should contain first item");
        assertTrue(html.contains("<li>Reliable</li>"), "Should contain second item");
        assertTrue(html.contains("<li>Secure</li>"), "Should contain third item");
    }

    @Test
    void testMixedListMarkersAfterColon() {
        String input = "The data types:1. primitives:a. boolean b. byte 2. references";
        String html = markdownService.processStructured(input).html();

        System.out.println("\nTest: Mixed list markers");
        System.out.println("Input: " + input);
        System.out.println("HTML: " + html);

        assertTrue(html.contains("<ol>"), "Should render as ordered list");
        assertTrue(html.contains("<li>primitives</li>"), "Should contain first item");
        assertTrue(html.contains("<li>references</li>"), "Should contain second item");
    }
    
    @Test
    void testListIntroducedByKeywords() {
        String input = "The benefits include 1. performance 2. reliability 3. scalability";
        String html = markdownService.processStructured(input).html();

        System.out.println("\nTest: List introduced by keywords");
        System.out.println("Input: " + input);
        System.out.println("HTML: " + html);

        assertTrue(html.contains("<ol>"), "Should render as ordered list");
        assertTrue(html.contains("<li>performance</li>"), "Should contain first item");
        assertTrue(html.contains("<li>reliability</li>"), "Should contain second item");
        assertTrue(html.contains("<li>scalability</li>"), "Should contain third item");
    }
    
    @Test
    void testDirectAttachmentToPunctuation() {
        String input = "See below:1.First item.2.Second item!3.Third item";
        String html = markdownService.processStructured(input).html();

        System.out.println("\nTest: Direct attachment to punctuation");
        System.out.println("Input: " + input);
        System.out.println("HTML: " + html);

        assertTrue(html.contains("<ol>"), "Should render as ordered list");
        assertTrue(html.contains("<li>First item</li>"), "Should contain first item");
        assertTrue(html.contains("<li>Second item</li>"), "Should contain second item");
        assertTrue(html.contains("<li>Third item</li>"), "Should contain third item");
    }
    
    @Test
    void testSpecialBulletCharacters() {
        String input = "Options:• First option• Second option• Third option";
        String html = markdownService.processStructured(input).html();

        System.out.println("\nTest: Special bullet characters");
        System.out.println("Input: " + input);
        System.out.println("HTML: " + html);

        assertTrue(html.contains("<ul>"), "Should render as unordered list");
        assertTrue(html.contains("<li>First option</li>"), "Should contain first item");
        assertTrue(html.contains("<li>Second option</li>"), "Should contain second item");
        assertTrue(html.contains("<li>Third option</li>"), "Should contain third item");
    }
    
    @Test
    void testNoFalsePositivesInSentences() {
        // These should NOT be converted to lists
        String input1 = "Java 1.8 introduced lambdas and streams.";
        String html1 = markdownService.processStructured(input1).html();
        assertFalse(html1.contains("<ol>"), "Should not create list for version numbers");
        assertFalse(html1.contains("<ul>"), "Should not create list for version numbers");

        String input2 = "The equation is x - y = 5.";
        String html2 = markdownService.processStructured(input2).html();
        assertFalse(html2.contains("<ol>"), "Should not create list for math expressions");
        assertFalse(html2.contains("<ul>"), "Should not create list for math expressions");

        String input3 = "Released in 2024. Updated features include Java 21.";
        String html3 = markdownService.processStructured(input3).html();
        assertFalse(html3.contains("<ol>"), "Should not create list for normal sentences");
        assertFalse(html3.contains("<ul>"), "Should not create list for normal sentences");

        System.out.println("\nTest: No false positives");
        System.out.println("Version number preserved: " + !html1.contains("<ol>") + !html1.contains("<ul>"));
        System.out.println("Math expression preserved: " + !html2.contains("<ol>") + !html2.contains("<ul>"));
        System.out.println("Normal sentences preserved: " + !html3.contains("<ol>") + !html3.contains("<ul>"));
    }
    
    @Test
    void testComplexRealWorldExample() {
        String input = "Java provides:1. Primitive types:a. boolean: true/false b. byte: 8-bit 2. Reference types:- Arrays- Classes- Interfaces";
        String html = markdownService.processStructured(input).html();

        System.out.println("\nTest: Complex real-world example");
        System.out.println("Input: " + input);
        System.out.println("HTML: " + html);

        // Should properly format all the nested lists
        assertTrue(html.contains("<ol>"), "Should contain ordered lists");
        assertTrue(html.contains("<ul>"), "Should contain unordered lists");
        assertTrue(html.contains("<li>Primitive types</li>"), "Should format main numbered list");
        assertTrue(html.contains("<li>boolean</li>"), "Should format nested letter list");
        assertTrue(html.contains("<li>byte</li>"), "Should continue letter list");
        assertTrue(html.contains("<li>Reference types</li>"), "Should continue numbered list");
        assertTrue(html.contains("<li>Arrays</li>"), "Should format nested dash list");
        assertTrue(html.contains("<li>Classes</li>"), "Should continue dash list");
        assertTrue(html.contains("<li>Interfaces</li>"), "Should continue dash list");
    }
}
