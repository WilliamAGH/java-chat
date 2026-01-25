package com.williamcallahan.javachat.domain;

import com.williamcallahan.javachat.support.DocumentContentAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SearchQualityLevel} enum behavior.
 */
class SearchQualityLevelTest {

    @Test
    void determineReturnsNoneForEmptyList() {
        SearchQualityLevel level = SearchQualityLevel.determine(List.of());
        assertThat(level).isEqualTo(SearchQualityLevel.NONE);
    }

    @Test
    void determineReturnsNoneForNullList() {
        SearchQualityLevel level = SearchQualityLevel.determine(null);
        assertThat(level).isEqualTo(SearchQualityLevel.NONE);
    }

    @Test
    void determineReturnsKeywordSearchWhenUrlContainsKeyword() {
        Document keywordDoc = new Document("some content", Map.of("url", "local-search://query"));
        SearchQualityLevel level = SearchQualityLevel.determine(
                DocumentContentAdapter.fromDocuments(List.of(keywordDoc)));
        assertThat(level).isEqualTo(SearchQualityLevel.KEYWORD_SEARCH);
    }

    @Test
    void determineReturnsHighQualityWhenAllDocsHaveSubstantialContent() {
        String longContent = "a".repeat(150);
        Document doc1 = new Document(longContent, Map.of("url", "https://example.com/doc1"));
        Document doc2 = new Document(longContent, Map.of("url", "https://example.com/doc2"));
        SearchQualityLevel level = SearchQualityLevel.determine(
                DocumentContentAdapter.fromDocuments(List.of(doc1, doc2)));
        assertThat(level).isEqualTo(SearchQualityLevel.HIGH_QUALITY);
    }

    @Test
    void determineReturnsMixedQualityWhenSomeDocsHaveShortContent() {
        String longContent = "a".repeat(150);
        String shortContent = "short";
        Document highQualityDoc = new Document(longContent, Map.of("url", "https://example.com/doc1"));
        Document lowQualityDoc = new Document(shortContent, Map.of("url", "https://example.com/doc2"));
        SearchQualityLevel level = SearchQualityLevel.determine(
                DocumentContentAdapter.fromDocuments(List.of(highQualityDoc, lowQualityDoc)));
        assertThat(level).isEqualTo(SearchQualityLevel.MIXED_QUALITY);
    }

    @Test
    void formatMessageReturnsCorrectStringForEachLevel() {
        assertThat(SearchQualityLevel.NONE.formatMessage(0, 0))
                .contains("No relevant documents");

        assertThat(SearchQualityLevel.KEYWORD_SEARCH.formatMessage(5, 0))
                .contains("5 documents")
                .contains("keyword search");

        assertThat(SearchQualityLevel.HIGH_QUALITY.formatMessage(3, 3))
                .contains("3 high-quality");

        assertThat(SearchQualityLevel.MIXED_QUALITY.formatMessage(5, 2))
                .contains("5 documents")
                .contains("2 high-quality");
    }

    @Test
    void describeQualityReturnsFormattedMessage() {
        String description = SearchQualityLevel.describeQuality(List.of());
        assertThat(description).isNotBlank();
        assertThat(description).contains("No relevant documents");
    }
}
