package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/** Verifies citation URL projection, deduplication identity, JSON shape, and conversion failures. */
@JsonTest
class RetrievalServiceCitationTest {

    private static final Logger RETRIEVAL_SERVICE_LOGGER = (Logger) LoggerFactory.getLogger(RetrievalService.class);

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void collapsesDocumentsResolvedToTheSameJavadocMemberAnchor() {
        String stringJavadocUrl = javaLangStringJavadocUrl();
        RetrievalService.CitationOutcome citationOutcome = citationService()
                .toCitations(List.of(
                        javadocCitationDocument("first-substring-chunk", "substring(int,int)"),
                        javadocCitationDocument("duplicate-substring-chunk", "substring(int,int)")));

        assertEquals(
                List.of(stringJavadocUrl),
                citationOutcome.citations().stream().map(Citation::getUrl).toList());
        assertEquals(
                "substring(int,int)", citationOutcome.citations().getFirst().getAnchor());
        assertEquals(0, citationOutcome.failedConversionCount());
    }

    @Test
    void retainsDistinctFullUrlIdentitiesWhileProjectingAnchorsOutOfTheUrl() {
        String stringJavadocUrl = javaLangStringJavadocUrl();
        RetrievalService.CitationOutcome citationOutcome = citationService()
                .toCitations(List.of(
                        javadocCitationDocument("substring-chunk", "substring(int,int)"),
                        javadocCitationDocument("char-at-chunk", "charAt(int)")));

        assertEquals(
                List.of(stringJavadocUrl, stringJavadocUrl),
                citationOutcome.citations().stream().map(Citation::getUrl).toList());
        assertEquals(
                List.of("substring(int,int)", "charAt(int)"),
                citationOutcome.citations().stream().map(Citation::getAnchor).toList());
        assertEquals(0, citationOutcome.failedConversionCount());
    }

    @Test
    void serializedCitationSplitsAtTheFirstFragmentDelimiterWithoutDecodingTheAnchor() {
        String citationPageUrl = "https://example.test/reference/arrays";
        String encodedCitationAnchor = "method%28java.lang.String%5B%5D%29#details%20section";
        Document encodedAnchorDocument = Document.builder()
                .id("encoded-anchor-document")
                .text("Array method reference")
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, citationPageUrl + "#" + encodedCitationAnchor)
                .metadata(QdrantPayloadFieldSchema.TITLE_FIELD, "Array method")
                .metadata(QdrantPayloadFieldSchema.DOC_TYPE_FIELD, "tutorial")
                .build();

        Citation projectedCitation = citationService()
                .toCitations(List.of(encodedAnchorDocument))
                .citations()
                .getFirst();
        JsonNode citationJson = objectMapper.valueToTree(projectedCitation);

        assertEquals(citationPageUrl, citationJson.path("url").textValue());
        assertEquals(encodedCitationAnchor, citationJson.path("anchor").textValue());
    }

    @Test
    void doesNotGenerateRecordAnchorFromUppercaseInvocationArguments() {
        String recordJavadocUrl =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst().remoteBaseUrl()
                        + "java.base/java/lang/Record.html";
        Document recordDocument = Document.builder()
                .id("record-equals-invocation")
                .text("return equals(this.SomeField, r.OTHER_FIELD);")
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, recordJavadocUrl)
                .metadata(QdrantPayloadFieldSchema.PACKAGE_FIELD, "java.lang")
                .metadata(QdrantPayloadFieldSchema.DOC_TYPE_FIELD, DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE)
                .build();

        RetrievalService.CitationOutcome citationOutcome = citationService().toCitations(List.of(recordDocument));

        assertEquals(
                List.of(recordJavadocUrl),
                citationOutcome.citations().stream().map(Citation::getUrl).toList());
        assertEquals(0, citationOutcome.failedConversionCount());
    }

    @Test
    void doesNotRefineMemberAnchorsOutsideApiDocs() {
        String stringJavadocUrl = javaLangStringJavadocUrl();
        Document tutorialDocument = Document.builder()
                .id("tutorial-substring-chunk")
                .text("substring(int,int)")
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, stringJavadocUrl)
                .metadata(QdrantPayloadFieldSchema.PACKAGE_FIELD, "java.lang")
                .metadata(QdrantPayloadFieldSchema.DOC_TYPE_FIELD, "tutorial")
                .build();

        RetrievalService.CitationOutcome citationOutcome = citationService().toCitations(List.of(tutorialDocument));

        assertEquals(
                List.of(stringJavadocUrl),
                citationOutcome.citations().stream().map(Citation::getUrl).toList());
        assertEquals(0, citationOutcome.failedConversionCount());
    }

    @Test
    void doesNotRefineNestedTypeUrlsOutsideApiDocs() {
        String mapJavadocUrl =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst().remoteBaseUrl()
                        + "java.base/java/util/Map.html";
        Document tutorialDocument = Document.builder()
                .id("tutorial-map-entry-chunk")
                .text("Map.Entry")
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, mapJavadocUrl)
                .metadata(QdrantPayloadFieldSchema.PACKAGE_FIELD, "java.util")
                .metadata(QdrantPayloadFieldSchema.DOC_TYPE_FIELD, "tutorial")
                .build();

        RetrievalService.CitationOutcome citationOutcome = citationService().toCitations(List.of(tutorialDocument));

        assertEquals(
                List.of(mapJavadocUrl),
                citationOutcome.citations().stream().map(Citation::getUrl).toList());
        assertEquals(0, citationOutcome.failedConversionCount());
    }

    @Test
    void collapsesFragmentlessDocumentsFromTheSamePage() {
        String stringJavadocUrl = javaLangStringJavadocUrl();
        RetrievalService.CitationOutcome citationOutcome = citationService()
                .toCitations(List.of(
                        javadocCitationDocument("first-class-overview-chunk", "Class overview"),
                        javadocCitationDocument("duplicate-class-overview-chunk", "Class overview")));

        assertEquals(
                List.of(stringJavadocUrl),
                citationOutcome.citations().stream().map(Citation::getUrl).toList());
        assertEquals(0, citationOutcome.failedConversionCount());
    }

    @Test
    void retainsDistinctPdfPageAnchors() {
        String pdfCitationUrl = "https://example.test/books/Reference.pdf";
        RetrievalService.CitationOutcome citationOutcome = citationService()
                .toCitations(List.of(
                        Document.builder()
                                .id("first-pdf-page-chunk")
                                .text("First PDF page")
                                .metadata(QdrantPayloadFieldSchema.URL_FIELD, pdfCitationUrl + "#page=1")
                                .build(),
                        Document.builder()
                                .id("second-pdf-page-chunk")
                                .text("Second PDF page")
                                .metadata(QdrantPayloadFieldSchema.URL_FIELD, pdfCitationUrl + "#page=2")
                                .build()));

        assertEquals(
                List.of(pdfCitationUrl, pdfCitationUrl),
                citationOutcome.citations().stream().map(Citation::getUrl).toList());
        assertEquals(
                List.of("page=1", "page=2"),
                citationOutcome.citations().stream().map(Citation::getAnchor).toList());
        assertEquals(0, citationOutcome.failedConversionCount());
    }

    @Test
    void reportsFailedConversionCountAndKeepsValidCitations() {
        RetrievalService retrievalService = citationService();
        Document malformedDocument = Document.builder()
                .id("malformed-doc")
                .text("Malformed metadata")
                .build();
        malformedDocument.getMetadata().put(QdrantPayloadFieldSchema.URL_FIELD, new BrokenUrlValue());
        malformedDocument.getMetadata().put(QdrantPayloadFieldSchema.TITLE_FIELD, "Broken citation");

        Document validDocument =
                Document.builder().id("valid-doc").text("Valid snippet").build();
        validDocument.getMetadata().put(QdrantPayloadFieldSchema.URL_FIELD, javaLangStringJavadocUrl());
        validDocument.getMetadata().put(QdrantPayloadFieldSchema.TITLE_FIELD, "String");

        RetrievalService.CitationOutcome citationOutcome;
        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(RETRIEVAL_SERVICE_LOGGER)) {
            citationOutcome = retrievalService.toCitations(List.of(malformedDocument, validDocument));

            assertEquals(2, expectedLogEvents.events().size());
            var conversionFailureWarning = expectedLogEvents.events().getFirst();
            assertEquals(Level.WARN, conversionFailureWarning.getLevel());
            assertEquals(
                    "Citation conversion failed (exceptionType=IllegalStateException,"
                            + " docUrl=[unprintable:BrokenUrlValue], docTitle=Broken citation)",
                    conversionFailureWarning.getFormattedMessage());
            assertNotNull(conversionFailureWarning.getThrowableProxy());
            assertEquals(
                    IllegalStateException.class.getName(),
                    conversionFailureWarning.getThrowableProxy().getClassName());

            var conversionSummaryWarning = expectedLogEvents.events().get(1);
            assertEquals(Level.WARN, conversionSummaryWarning.getLevel());
            assertEquals(
                    "Citation conversion completed with 1 failure(s) out of 2 documents",
                    conversionSummaryWarning.getFormattedMessage());
            assertNull(conversionSummaryWarning.getThrowableProxy());
        }

        assertEquals(1, citationOutcome.citations().size());
        assertEquals(1, citationOutcome.failedConversionCount());
        assertEquals("String", citationOutcome.citations().getFirst().getTitle());
        assertTrue(citationOutcome.citations().getFirst().getUrl().contains("docs.oracle.com"));

        CitationConversionFailureException conversionFailure =
                assertThrows(CitationConversionFailureException.class, citationOutcome::citationsOrThrow);
        assertEquals(1, conversionFailure.failedConversionCount());
    }

    private static RetrievalService citationService() {
        return new RetrievalService(
                mock(HybridSearchService.class),
                new AppProperties(),
                mock(RerankerService.class),
                mock(DocumentFactory.class));
    }

    private static Document javadocCitationDocument(String documentId, String sourceText) {
        return Document.builder()
                .id(documentId)
                .text(sourceText)
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, javaLangStringJavadocUrl())
                .metadata(QdrantPayloadFieldSchema.PACKAGE_FIELD, "java.lang")
                .metadata(QdrantPayloadFieldSchema.DOC_TYPE_FIELD, DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE)
                .build();
    }

    private static String javaLangStringJavadocUrl() {
        return DocsSourceRegistry.javaApiDocumentationSources().getFirst().remoteBaseUrl()
                + "java.base/java/lang/String.html";
    }

    /** Simulates malformed metadata values whose string conversion fails at runtime. */
    private static final class BrokenUrlValue {
        @Override
        public String toString() {
            throw new IllegalStateException("broken url value");
        }
    }
}
