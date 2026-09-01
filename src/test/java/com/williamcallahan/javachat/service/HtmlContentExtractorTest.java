package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.williamcallahan.javachat.domain.javaapi.JavadocMemberAnchor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

/**
 * Verifies HTML extraction preserves code formatting while normalizing prose whitespace.
 */
class HtmlContentExtractorTest {

    private static final JavadocMemberAnchor MAP_MEMBER_ANCHOR =
            new JavadocMemberAnchor("map(java.util.function.Function)");
    private static final JavadocMemberAnchor MAP_TO_DOUBLE_MEMBER_ANCHOR =
            new JavadocMemberAnchor("mapToDouble(java.util.function.ToDoubleFunction)");

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
    void retainsMainContainerWhoseArticleFollowsSkipLinkText() {
        Document document = Jsoup.parse("""
            <html><body><main>
              <div>
                <a href="#article">Skip to content</a>
                <article id="article">
                  <h1>Show source citations in responses</h1>
                  <p>Display source citations alongside generated answers.
                    <a href="/source">Show source</a>
                  </p>
                </article>
              </div>
            </main></body></html>
            """);
        HtmlContentExtractor extractor = new HtmlContentExtractor();

        String extractedText = extractor.extractCleanContent(document);

        assertTrue(extractedText.contains("Show source citations in responses"));
        assertTrue(extractedText.contains("Display source citations alongside generated answers."));
        assertTrue(extractedText.contains("Show source"));
        assertFalse(extractedText.contains("Skip to content"));
        assertEquals(2, document.select("a").size());
        assertEquals("Skip to content", document.selectFirst("a[href=#article]").text());
    }

    @Test
    void removesSkipLinkBeforeFlatteningDirectArticleContent() {
        Document document = Jsoup.parse("""
            <html><body><main>
              <article id="article">
                <a href="#article">Skip to main content</a>
                <h1>Article heading</h1>
                <p>Article body with <a href="/source">Show details</a>.</p>
              </article>
            </main></body></html>
            """);
        HtmlContentExtractor extractor = new HtmlContentExtractor();

        String extractedText = extractor.extractCleanContent(document);

        assertTrue(extractedText.contains("Article heading"));
        assertTrue(extractedText.contains("Article body with Show details."));
        assertFalse(extractedText.contains("Skip to main content"));
        assertEquals(2, document.select("a").size());
    }

    @Test
    void retainsShortDirectShowLink() {
        Document document = Jsoup.parse("""
            <html><body><main>
              <h1>Source controls</h1>
              <a href="/source">Show source</a>
            </main></body></html>
            """);

        String extractedText = new HtmlContentExtractor().extractCleanContent(document);

        assertTrue(extractedText.contains("Show source"));
    }

    @Test
    void extractsSerializedFormDetailsNestedInsideJavadocLists() {
        Document document = Jsoup.parse("""
            <html><head><title>Serialized Form (Spring AI Parent 1.1.2 API)</title></head>
            <body class="serialized-form-page">
              <main>
                <div class="header"><h1>Serialized Form</h1></div>
                <ul class="block-list">
                  <li>
                    <section class="serialized-package-container">
                      <h2>Package org.springframework.ai.mcp</h2>
                      <ul class="block-list">
                        <li>
                          <section class="serialized-class-details">
                            <h3>Class org.springframework.ai.mcp.McpToolsChangedEvent</h3>
                            <div class="type-signature">class McpToolsChangedEvent implements Serializable</div>
                            <section class="detail">
                              <h4>Serialized Fields</h4>
                              <ul class="block-list">
                                <li><h5>connectionName</h5><pre>String connectionName</pre></li>
                                <li><h5>tools</h5><pre>List&lt;McpSchema.Tool&gt; tools</pre></li>
                              </ul>
                            </section>
                          </section>
                        </li>
                      </ul>
                    </section>
                  </li>
                </ul>
              </main>
            </body></html>
            """);
        HtmlContentExtractor extractor = new HtmlContentExtractor();

        String extractedText = extractor.extractCleanContent(document);

        assertTrue(extractedText.contains("Package org.springframework.ai.mcp"));
        assertTrue(extractedText.contains("Class org.springframework.ai.mcp.McpToolsChangedEvent"));
        assertTrue(extractedText.contains("String connectionName"));
        assertTrue(extractedText.contains("List<McpSchema.Tool> tools"));
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
    void skipsBlankMemberAnchorWithoutDroppingValidSiblings() {
        Document document = Jsoup.parse("""
            <html><body class="class-declaration-page"><main>
              <section class="detail" id="">
                <div class="member-signature">void malformed()</div>
              </section>
              <section class="detail" id="map(java.util.function.Function)">
                <div class="member-signature">Stream map(Function mapper)</div>
              </section>
            </main></body></html>
            """);
        HtmlContentExtractor extractor = new HtmlContentExtractor();

        JavaApiPageExtraction extraction = extractor.extractJavaApiPage(document);

        assertEquals(1, extraction.anchoredSections().size());
        assertEquals(MAP_MEMBER_ANCHOR, extraction.anchoredSections().getFirst().anchor());
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
    void explicitlyExcludesJavadocFramesetNavigationPages() {
        Document document = Jsoup.parse("""
            <html><head><title>Library API</title></head>
              <frameset cols="20%,80%">
                <frame src="overview-frame.html">
                <frame src="overview-summary.html">
                <noframes><p>Link to <a href="overview-summary.html">Non-frame version</a>.</p></noframes>
              </frameset>
            </html>
            """);
        HtmlContentExtractor extractor = new HtmlContentExtractor();

        JavaApiPageExtraction extraction = extractor.extractJavaApiPage(document);

        assertEquals(JavaApiPageDisposition.EXCLUDED_NAVIGATION_PAGE, extraction.disposition());
        assertTrue(extraction.excluded());
        assertTrue(extraction.overviewText().isEmpty());
        assertTrue(extraction.anchoredSections().isEmpty());
        assertTrue(document.selectFirst("frameset") != null, "Extraction must not mutate frameset DOM");
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
