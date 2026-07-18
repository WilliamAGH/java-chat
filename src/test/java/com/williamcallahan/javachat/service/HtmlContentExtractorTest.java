package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

/**
 * Verifies HTML extraction preserves code formatting while normalizing prose whitespace.
 */
class HtmlContentExtractorTest {

    private static final String MAP_MEMBER_ANCHOR = "map(java.util.function.Function)";
    private static final String MAP_TO_DOUBLE_MEMBER_ANCHOR = "mapToDouble(java.util.function.ToDoubleFunction)";

    @Test
    void preservesCodeIndentationAndNormalizesProse() {
        String html = """
            <html><body>
              <pre>    int x = 1;\n\tint y = 2;\n</pre>
              <p>Text  with   spaces</p>
            </body></html>
            """;
        Document document = Jsoup.parse(html);
        HtmlContentExtractor extractor = new HtmlContentExtractor();

        String extractedText = extractor.extractCleanContent(document);

        assertTrue(extractedText.contains("```"), "Should include fenced code markers");
        assertTrue(extractedText.contains("    int x = 1;"), "Should preserve spaces in code blocks");
        assertTrue(extractedText.contains("\tint y = 2;"), "Should preserve tabs in code blocks");
        assertTrue(extractedText.contains("Text with spaces"), "Should normalize prose spacing");
        assertTrue(document.selectFirst("nav") == null, "Fixture should not contain a navigation element");
    }

    @Test
    void extractsModernClassMembersWithTheirExactDomAnchorsInSourceOrder() {
        Document document = Jsoup.parse("""
            <html><head><title>Stream</title></head>
            <body class="class-declaration-page">
              <nav>Skip navigation links</nav>
              <main>
                <div class="header"><h1 class="title">Interface Stream&lt;T&gt;</h1></div>
                <section class="class-description" id="class-description">
                  <div class="type-signature">public interface Stream&lt;T&gt;</div>
                  <div class="block">A sequence of elements.</div>
                </section>
                <section class="detail" id="map(java.util.function.Function)">
                  <h3>map</h3>
                  <div class="member-signature">&lt;R&gt; Stream&lt;R&gt; map(Function&lt;? super T, ? extends R&gt; mapper)</div>
                  <div class="block">Returns a stream consisting of mapping results.</div>
                </section>
                <section class="detail" id="mapToDouble(java.util.function.ToDoubleFunction)">
                  <h3>mapToDouble</h3>
                  <div class="member-signature">DoubleStream mapToDouble(ToDoubleFunction&lt;? super T&gt; mapper)</div>
                  <div class="block">Returns a DoubleStream containing mapped results.</div>
                </section>
              </main>
            </body></html>
            """);
        HtmlContentExtractor extractor = new HtmlContentExtractor();

        JavaApiPageExtraction extraction = extractor.extractJavaApiPage(document);

        assertEquals(JavaApiPageDisposition.INCLUDED, extraction.disposition());
        assertFalse(extraction.excluded());
        assertTrue(extraction.overviewText().contains("Interface Stream<T>"));
        assertTrue(extraction.overviewText().contains("A sequence of elements."));
        assertEquals(2, extraction.anchoredSections().size());
        assertEquals(MAP_MEMBER_ANCHOR, extraction.anchoredSections().getFirst().anchor());
        assertTrue(extraction.anchoredSections().getFirst().text().contains("map(Function"));
        assertTrue(extraction.anchoredSections().getFirst().text().contains("mapping results"));
        assertEquals(
                MAP_TO_DOUBLE_MEMBER_ANCHOR,
                extraction.anchoredSections().get(1).anchor());
        assertTrue(extraction.anchoredSections().get(1).text().contains("mapToDouble"));
        assertTrue(document.selectFirst("nav") != null, "Extraction must not mutate the parsed document");
    }

    @Test
    void explicitlyExcludesModernClassUsePages() {
        Document document = Jsoup.parse("""
            <html><body class="class-use-page"><main>
              <section class="detail" id="java.util">Types that use List.</section>
            </main></body></html>
            """);
        HtmlContentExtractor extractor = new HtmlContentExtractor();

        JavaApiPageExtraction extraction = extractor.extractJavaApiPage(document);

        assertEquals(JavaApiPageDisposition.EXCLUDED_CLASS_USE_PAGE, extraction.disposition());
        assertTrue(extraction.excluded());
        assertTrue(extraction.overviewText().isEmpty());
        assertTrue(extraction.anchoredSections().isEmpty());
        assertTrue(document.selectFirst("section.detail") != null, "Extraction must not mutate class-use DOM");
    }

    @Test
    void retainsPackagePagesAsUnanchoredJavaApiOverviews() {
        Document document = Jsoup.parse("""
            <html><body class="package-declaration-page">
              <nav>Skip navigation links</nav>
              <main><div class="header"><h1 class="title">Package java.util</h1></div>
                <section class="package-description"><div class="block">Contains collection types.</div></section>
              </main>
            </body></html>
            """);
        HtmlContentExtractor extractor = new HtmlContentExtractor();

        JavaApiPageExtraction extraction = extractor.extractJavaApiPage(document);

        assertEquals(JavaApiPageDisposition.INCLUDED, extraction.disposition());
        assertFalse(extraction.excluded());
        assertTrue(extraction.overviewText().contains("Package java.util"));
        assertTrue(extraction.overviewText().contains("Contains collection types."));
        assertTrue(extraction.anchoredSections().isEmpty());
        assertTrue(document.selectFirst("nav") != null, "Extraction must use a clone before removing navigation");
    }
}
