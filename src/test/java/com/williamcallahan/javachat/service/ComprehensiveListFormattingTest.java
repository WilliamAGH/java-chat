package com.williamcallahan.javachat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

class ComprehensiveListFormattingTest {
    
    private MarkdownService markdownService;
    
    @BeforeEach
    void setUp() {
        markdownService = new MarkdownService();
    }
    
    @Test
    void testNumberedListWithPeriod() {
        String input = "The types are:1. boolean 2. byte 3. int 4. long";
        String result = markdownService.preprocessMarkdown(input);
        
        System.out.println("Test: Numbered list with periods");
        System.out.println("Input: " + input);
        System.out.println("Output: " + result);
        
        assertTrue(result.contains("are:\n\n1. boolean"), "Should break before first numbered item");
        assertTrue(result.contains("\n2. byte"), "Should break before second item");
        assertTrue(result.contains("\n3. int"), "Should break before third item");
        assertTrue(result.contains("\n4. long"), "Should break before fourth item");
    }
    
    @Test
    void testNumberedListWithParenthesis() {
        String input = "The steps include:1) Setup 2) Configure 3) Deploy";
        String result = markdownService.preprocessMarkdown(input);
        
        System.out.println("\nTest: Numbered list with parenthesis");
        System.out.println("Input: " + input);
        System.out.println("Output: " + result);
        
        assertTrue(result.contains("include:\n\n1) Setup"), "Should break before 1)");
        assertTrue(result.contains("\n2) Configure"), "Should break before 2)");
        assertTrue(result.contains("\n3) Deploy"), "Should break before 3)");
    }
    
    @Test
    void testRomanNumeralsLowercase() {
        String input = "The stages are:i. Planning ii. Development iii. Testing";
        String result = markdownService.preprocessMarkdown(input);
        
        System.out.println("\nTest: Roman numerals (lowercase)");
        System.out.println("Input: " + input);
        System.out.println("Output: " + result);
        
        assertTrue(result.contains("are:\n\ni. Planning"), "Should break before i.");
        assertTrue(result.contains("\nii. Development"), "Should break before ii.");
        assertTrue(result.contains("\niii. Testing"), "Should break before iii.");
    }
    
    @Test
    void testLetterListLowercase() {
        String input = "Options include:a. First option b. Second option c. Third option";
        String result = markdownService.preprocessMarkdown(input);
        
        System.out.println("\nTest: Letter list (lowercase)");
        System.out.println("Input: " + input);
        System.out.println("Output: " + result);
        
        assertTrue(result.contains("include:\n\na. First"), "Should break before a.");
        assertTrue(result.contains("\nb. Second"), "Should break before b.");
        assertTrue(result.contains("\nc. Third"), "Should break before c.");
    }
    
    @Test
    void testDashBulletList() {
        String input = "Features:- Fast processing- High accuracy- Low latency";
        String result = markdownService.preprocessMarkdown(input);
        
        System.out.println("\nTest: Dash bullet list");
        System.out.println("Input: " + input);
        System.out.println("Output: " + result);
        
        assertTrue(result.contains("Features:\n\n- Fast"), "Should break before first dash");
        assertTrue(result.contains("\n- High"), "Should break before second dash");
        assertTrue(result.contains("\n- Low"), "Should break before third dash");
    }
    
    @Test
    void testAsteriskBulletList() {
        String input = "Benefits are:* Cost effective* Time saving* Easy to use";
        String result = markdownService.preprocessMarkdown(input);
        
        System.out.println("\nTest: Asterisk bullet list");
        System.out.println("Input: " + input);
        System.out.println("Output: " + result);
        
        assertTrue(result.contains("are:\n\n* Cost"), "Should break before first asterisk");
        assertTrue(result.contains("\n* Time"), "Should break before second asterisk");
        assertTrue(result.contains("\n* Easy"), "Should break before third asterisk");
    }
    
    @Test
    void testPlusBulletList() {
        String input = "Advantages:+ Scalable+ Reliable+ Secure";
        String result = markdownService.preprocessMarkdown(input);
        
        System.out.println("\nTest: Plus bullet list");
        System.out.println("Input: " + input);
        System.out.println("Output: " + result);
        
        assertTrue(result.contains("Advantages:\n\n+ Scalable"), "Should break before first plus");
        assertTrue(result.contains("\n+ Reliable"), "Should break before second plus");
        assertTrue(result.contains("\n+ Secure"), "Should break before third plus");
    }
    
    @Test
    void testMixedListMarkersAfterColon() {
        String input = "The data types:1. primitives:a. boolean b. byte 2. references";
        String result = markdownService.preprocessMarkdown(input);
        
        System.out.println("\nTest: Mixed list markers");
        System.out.println("Input: " + input);
        System.out.println("Output: " + result);
        
        assertTrue(result.contains("types:\n\n1. primitives"), "Should break before numbered item");
        assertTrue(result.contains(":\n\na. boolean"), "Should break before letter item");
        assertTrue(result.contains("\nb. byte"), "Should break before second letter");
        assertTrue(result.contains("\n2. references"), "Should break before second number");
    }
    
    @Test
    void testListIntroducedByKeywords() {
        String input = "The benefits include 1. performance 2. reliability 3. scalability";
        String result = markdownService.preprocessMarkdown(input);
        
        System.out.println("\nTest: List introduced by keywords");
        System.out.println("Input: " + input);
        System.out.println("Output: " + result);
        
        assertTrue(result.contains("include\n\n1. performance"), "Should break after 'include'");
        assertTrue(result.contains("\n2. reliability"), "Should break before item 2");
        assertTrue(result.contains("\n3. scalability"), "Should break before item 3");
    }
    
    @Test
    void testDirectAttachmentToPunctuation() {
        String input = "See below:1.First item.2.Second item!3.Third item";
        String result = markdownService.preprocessMarkdown(input);
        
        System.out.println("\nTest: Direct attachment to punctuation");
        System.out.println("Input: " + input);
        System.out.println("Output: " + result);
        
        assertTrue(result.contains(":\n\n1."), "Should break after colon");
        assertTrue(result.contains(".\n\n2."), "Should break after period");
        assertTrue(result.contains("!\n\n3."), "Should break after exclamation");
    }
    
    @Test
    void testSpecialBulletCharacters() {
        String input = "Options:• First option• Second option• Third option";
        String result = markdownService.preprocessMarkdown(input);
        
        System.out.println("\nTest: Special bullet characters");
        System.out.println("Input: " + input);
        System.out.println("Output: " + result);
        
        assertTrue(result.contains(":\n\n• First"), "Should break before bullet point");
        assertTrue(result.contains("\n• Second"), "Should break before second bullet");
        assertTrue(result.contains("\n• Third"), "Should break before third bullet");
    }
    
    @Test
    void testNoFalsePositivesInSentences() {
        // These should NOT be converted to lists
        String input1 = "Java 1.8 introduced lambdas and streams.";
        String result1 = markdownService.preprocessMarkdown(input1);
        assertFalse(result1.contains("\n\n8"), "Should not break version numbers");
        
        String input2 = "The equation is x - y = 5.";
        String result2 = markdownService.preprocessMarkdown(input2);
        assertFalse(result2.contains("\n- y"), "Should not break math expressions");
        
        String input3 = "Released in 2024. Updated features include Java 21.";
        String result3 = markdownService.preprocessMarkdown(input3);
        assertFalse(result3.contains("\n\nUpdated"), "Should not break normal sentences");
        
        System.out.println("\nTest: No false positives");
        System.out.println("Version number preserved: " + !result1.contains("\n\n8"));
        System.out.println("Math expression preserved: " + !result2.contains("\n- y"));
        System.out.println("Normal sentences preserved: " + !result3.contains("\n\nUpdated"));
    }
    
    @Test
    void testComplexRealWorldExample() {
        String input = "Java provides:1. Primitive types:a. boolean: true/false b. byte: 8-bit 2. Reference types:- Arrays- Classes- Interfaces";
        String result = markdownService.preprocessMarkdown(input);
        
        System.out.println("\nTest: Complex real-world example");
        System.out.println("Input: " + input);
        System.out.println("Output: " + result);
        
        // Should properly format all the nested lists
        assertTrue(result.contains("provides:\n\n1. Primitive"), "Should format main numbered list");
        assertTrue(result.contains("types:\n\na. boolean"), "Should format nested letter list");
        assertTrue(result.contains("\nb. byte"), "Should continue letter list");
        assertTrue(result.contains("\n2. Reference"), "Should continue numbered list");
        assertTrue(result.contains("types:\n\n- Arrays"), "Should format nested dash list");
        assertTrue(result.contains("\n- Classes"), "Should continue dash list");
        assertTrue(result.contains("\n- Interfaces"), "Should continue dash list");
    }
}