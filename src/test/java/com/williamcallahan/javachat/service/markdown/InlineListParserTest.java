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
        InlineListParser.Conversion result = InlineListParser.tryConvert("1. First 2. Second 3. Third");

        assertNotNull(result);
        assertEquals("ol", result.primaryListElement().tagName());
        assertEquals(3, result.primaryListElement().children().size());
        assertEquals("First", result.primaryListElement().child(0).text());
        assertEquals("Second", result.primaryListElement().child(1).text());
        assertEquals("Third", result.primaryListElement().child(2).text());
    }

    @Test
    void tryConvert_romanMarkers_createsOrderedList() {
        InlineListParser.Conversion result = InlineListParser.tryConvert("i. Alpha ii. Beta iii. Gamma");

        assertNotNull(result);
        assertEquals("ol", result.primaryListElement().tagName());
        assertEquals(3, result.primaryListElement().children().size());
        assertEquals("Alpha", result.primaryListElement().child(0).text());
        assertEquals("Beta", result.primaryListElement().child(1).text());
        assertEquals("Gamma", result.primaryListElement().child(2).text());
    }

    @Test
    void tryConvert_letterMarkers_createsOrderedList() {
        InlineListParser.Conversion result = InlineListParser.tryConvert("a. Apple b. Banana c. Cherry");

        assertNotNull(result);
        assertEquals("ol", result.primaryListElement().tagName());
        assertEquals(3, result.primaryListElement().children().size());
        assertEquals("Apple", result.primaryListElement().child(0).text());
        assertEquals("Banana", result.primaryListElement().child(1).text());
        assertEquals("Cherry", result.primaryListElement().child(2).text());
    }

    @Test
    void tryConvert_dashMarkers_createsBulletList() {
        InlineListParser.Conversion result = InlineListParser.tryConvert("- Item one - Item two - Item three");

        assertNotNull(result);
        assertEquals("ul", result.primaryListElement().tagName());
        assertEquals(3, result.primaryListElement().children().size());
    }

    @Test
    void tryConvert_asteriskMarkers_createsBulletList() {
        InlineListParser.Conversion result = InlineListParser.tryConvert("* First * Second * Third");

        assertNotNull(result);
        assertEquals("ul", result.primaryListElement().tagName());
        assertEquals(3, result.primaryListElement().children().size());
    }

    @Test
    void tryConvert_withLeadingText_extractsPrefix() {
        InlineListParser.Conversion result = InlineListParser.tryConvert("Key points: 1. First 2. Second");

        assertNotNull(result);
        assertEquals("Key points:", result.leadingText());
        assertEquals("ol", result.primaryListElement().tagName());
        assertEquals(2, result.primaryListElement().children().size());
    }

    @Test
    void tryConvert_singleMarker_returnsNull() {
        InlineListParser.Conversion result = InlineListParser.tryConvert("1. Only one item here");

        assertNull(result);
    }

    @Test
    void tryConvert_noMarkers_returnsNull() {
        InlineListParser.Conversion result = InlineListParser.tryConvert("This is just plain text");

        assertNull(result);
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
        InlineListParser.Conversion result = InlineListParser.tryConvert(
            "1. Parent: a. Child one b. Child two 2. Another"
        );

        assertNotNull(result);
        assertEquals("ol", result.primaryListElement().tagName());
        assertEquals(2, result.primaryListElement().children().size());
        // Nested lists are extracted as additional elements
        assertNotNull(result.additionalListElements());
    }

    @Test
    void tryConvert_parenthesisMarkers_recognized() {
        InlineListParser.Conversion result = InlineListParser.tryConvert("1) First 2) Second");

        assertNotNull(result);
        assertEquals("ol", result.primaryListElement().tagName());
        assertEquals(2, result.primaryListElement().children().size());
    }

    @Test
    void tryConvert_versionNumbers_notTreatedAsMarkers() {
        // "1.8" should not be treated as marker "1." followed by "8"
        InlineListParser.Conversion result = InlineListParser.tryConvert("Java 1.8 is old");

        assertNull(result);
    }

    @Test
    void tryConvert_bulletAfterColon_recognized() {
        InlineListParser.Conversion result = InlineListParser.tryConvert("Items: - First - Second");

        assertNotNull(result);
        assertEquals("ul", result.primaryListElement().tagName());
        assertEquals("Items:", result.leadingText());
    }

    @Test
    void tryConvert_normalizesTrailingPunctuation() {
        InlineListParser.Conversion result = InlineListParser.tryConvert("1. First. 2. Second!");

        assertNotNull(result);
        // Trailing punctuation should be stripped from item labels
        assertEquals("First", result.primaryListElement().child(0).text());
        assertEquals("Second", result.primaryListElement().child(1).text());
    }
}
