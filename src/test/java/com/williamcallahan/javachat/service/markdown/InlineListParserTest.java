package com.williamcallahan.javachat.service.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests inline list marker parsing and conversion to HTML list elements.
 */
class InlineListParserTest {

    @Test
    void tryConvert_numericMarkers_createsOrderedList() {
        InlineListParser.Conversion conversion = InlineListParser.tryConvert("1. First 2. Second 3. Third");

        assertNotNull(conversion);
        assertEquals("ol", conversion.primaryListElement().tagName());
        assertEquals(3, conversion.primaryListElement().children().size());
        assertEquals("First", conversion.primaryListElement().child(0).text());
        assertEquals("Second", conversion.primaryListElement().child(1).text());
        assertEquals("Third", conversion.primaryListElement().child(2).text());
    }

    @Test
    void tryConvert_numericMarkers_allowNumericContentStart() {
        InlineListParser.Conversion conversion = InlineListParser.tryConvert("1. 2024 plan 2. 2025 plan");

        assertNotNull(conversion);
        assertEquals("2024 plan", conversion.primaryListElement().child(0).text());
        assertEquals("2025 plan", conversion.primaryListElement().child(1).text());
    }

    @Test
    void tryConvert_numericMarkers_allowSymbolContentStart() {
        InlineListParser.Conversion conversion = InlineListParser.tryConvert("1. $5 fee 2. $6 fee");

        assertNotNull(conversion);
        assertEquals("$5 fee", conversion.primaryListElement().child(0).text());
        assertEquals("$6 fee", conversion.primaryListElement().child(1).text());
    }

    @Test
    void tryConvert_romanMarkers_createsOrderedList() {
        InlineListParser.Conversion conversion = InlineListParser.tryConvert("i. Alpha ii. Beta iii. Gamma");

        assertNotNull(conversion);
        assertEquals("ol", conversion.primaryListElement().tagName());
        assertEquals(3, conversion.primaryListElement().children().size());
        assertEquals("Alpha", conversion.primaryListElement().child(0).text());
        assertEquals("Beta", conversion.primaryListElement().child(1).text());
        assertEquals("Gamma", conversion.primaryListElement().child(2).text());
    }

    @Test
    void tryConvert_letterMarkers_createsOrderedList() {
        InlineListParser.Conversion conversion = InlineListParser.tryConvert("a. Apple b. Banana c. Cherry");

        assertNotNull(conversion);
        assertEquals("ol", conversion.primaryListElement().tagName());
        assertEquals(3, conversion.primaryListElement().children().size());
        assertEquals("Apple", conversion.primaryListElement().child(0).text());
        assertEquals("Banana", conversion.primaryListElement().child(1).text());
        assertEquals("Cherry", conversion.primaryListElement().child(2).text());
    }

    @Test
    void tryConvert_dashMarkers_createsBulletList() {
        InlineListParser.Conversion conversion = InlineListParser.tryConvert("- Item one - Item two - Item three");

        assertNotNull(conversion);
        assertEquals("ul", conversion.primaryListElement().tagName());
        assertEquals(3, conversion.primaryListElement().children().size());
    }

    @Test
    void tryConvert_asteriskMarkers_createsBulletList() {
        InlineListParser.Conversion conversion = InlineListParser.tryConvert("* First * Second * Third");

        assertNotNull(conversion);
        assertEquals("ul", conversion.primaryListElement().tagName());
        assertEquals(3, conversion.primaryListElement().children().size());
    }

    @Test
    void tryConvert_withLeadingText_extractsPrefix() {
        InlineListParser.Conversion conversion = InlineListParser.tryConvert("Key points: 1. First 2. Second");

        assertNotNull(conversion);
        assertEquals("Key points:", conversion.leadingText());
        assertEquals("ol", conversion.primaryListElement().tagName());
        assertEquals(2, conversion.primaryListElement().children().size());
    }

    @Test
    void tryConvert_singleMarker_returnsNull() {
        InlineListParser.Conversion conversion = InlineListParser.tryConvert("1. Only one item here");

        assertNull(conversion);
    }

    @Test
    void tryConvert_noMarkers_returnsNull() {
        InlineListParser.Conversion conversion = InlineListParser.tryConvert("This is just plain text");

        assertNull(conversion);
    }

    @Test
    void tryConvert_nullInput_returnsNull() {
        assertNull(InlineListParser.tryConvert(null));
    }

    @Test
    void tryConvert_emptyInput_returnsNull() {
        assertNull(InlineListParser.tryConvert(""));
        assertNull(InlineListParser.tryConvert("   "));
    }

    @Test
    void tryConvert_nestedList_extractsNestedSegment() {
        InlineListParser.Conversion conversion = InlineListParser.tryConvert(
            "1. Parent: a. Child one b. Child two 2. Another"
        );

        assertNotNull(conversion);
        assertEquals("ol", conversion.primaryListElement().tagName());
        assertEquals(2, conversion.primaryListElement().children().size());
        // Nested lists are extracted as additional elements
        assertNotNull(conversion.additionalListElements());
    }

    @Test
    void tryConvert_parenthesisMarkers_recognized() {
        InlineListParser.Conversion conversion = InlineListParser.tryConvert("1) First 2) Second");

        assertNotNull(conversion);
        assertEquals("ol", conversion.primaryListElement().tagName());
        assertEquals(2, conversion.primaryListElement().children().size());
    }

    @Test
    void tryConvert_versionNumbers_notTreatedAsMarkers() {
        // "1.8" should not be treated as marker "1." followed by "8"
        InlineListParser.Conversion conversion = InlineListParser.tryConvert("Java 1.8 is old");

        assertNull(conversion);
    }

    @Test
    void tryConvert_bulletAfterColon_recognized() {
        InlineListParser.Conversion conversion = InlineListParser.tryConvert("Items: - First - Second");

        assertNotNull(conversion);
        assertEquals("ul", conversion.primaryListElement().tagName());
        assertEquals("Items:", conversion.leadingText());
    }

    @Test
    void tryConvert_normalizesTrailingPunctuation() {
        InlineListParser.Conversion conversion = InlineListParser.tryConvert("1. First. 2. Second!");

        assertNotNull(conversion);
        // Trailing punctuation should be stripped from item labels
        assertEquals("First", conversion.primaryListElement().child(0).text());
        assertEquals("Second", conversion.primaryListElement().child(1).text());
    }

    @Test
    void tryConvert_extractsTrailingTextAfterList() {
        InlineListParser.Conversion conversion = InlineListParser.tryConvert(
            "1. First 2. Second. After the list wraps up"
        );

        assertNotNull(conversion);
        assertEquals("Second", conversion.primaryListElement().child(1).text());
        assertEquals("After the list wraps up", conversion.trailingText());
    }
}
