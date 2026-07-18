package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.williamcallahan.javachat.config.DocsSourceRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/** Verifies deterministic local ordering of sparse static-citation candidates. */
class CitationCandidateRankerTest {

    @Test
    void prioritizesExactJavadocTypePagesOverHigherRankedMethodOnlyMatches() {
        Document classFileCandidate =
                javadocCandidate("class-file", "ClassFileFormatVersion factory of()", "ClassFileFormatVersion.html");
        Document firstListCandidate = javadocCandidate("list-first", "static <E> List<E> of(E element)", "List.html");
        Document secondListCandidate =
                javadocCandidate("list-second", "static <E> List<E> of(E first, E second)", "List.html");

        List<Document> orderedCandidates = CitationCandidateRanker.orderForCitationQuery(
                "What does Java List.of() return?",
                List.of(classFileCandidate, firstListCandidate, secondListCandidate));

        assertEquals(
                List.of("list-first", "list-second", "class-file"),
                orderedCandidates.stream().map(Document::getId).toList());
    }

    @Test
    void recognizesUnparenthesizedTypeMethodSelectorsDuringCandidateOrdering() {
        Document classFileCandidate =
                javadocCandidate("class-file", "A utility map() method", "ClassFileFormatVersion.html");
        Document streamCandidate = javadocCandidate("stream", "<R> Stream<R> map(Function mapper)", "Stream.html");

        List<Document> orderedCandidates = CitationCandidateRanker.orderForCitationQuery(
                "Explain Stream.map in Java", List.of(classFileCandidate, streamCandidate));

        assertEquals(
                List.of("stream", "class-file"),
                orderedCandidates.stream().map(Document::getId).toList());
    }

    @Test
    void matchesTheDeclaredPackageWhenASelectorIsFullyQualified() {
        Document sqlDateCandidate = javadocCandidate(
                "sql-date", "String toString()", "java.sql", "Date.html", DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE);
        Document utilDateCandidate = javadocCandidate(
                "util-date", "String toString()", "java.util", "Date.html", DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE);

        List<Document> orderedCandidates = CitationCandidateRanker.orderForCitationQuery(
                "What does java.util.Date.toString return?", List.of(sqlDateCandidate, utilDateCandidate));

        assertEquals(
                List.of("util-date", "sql-date"),
                orderedCandidates.stream().map(Document::getId).toList());
    }

    @Test
    void excludesNonApiDocumentationFromSelectorEvidence() {
        Document apiMethodOnlyCandidate = javadocCandidate(
                "api-method",
                "A utility of() method",
                "java.lang",
                "Object.html",
                DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE);
        Document tutorialListCandidate = javadocCandidate(
                "tutorial-list", "static <E> List<E> of(E element)", "java.util", "List.html", "tutorial");

        List<Document> orderedCandidates = CitationCandidateRanker.orderForCitationQuery(
                "What does List.of return?", List.of(apiMethodOnlyCandidate, tutorialListCandidate));

        assertEquals(
                List.of("api-method", "tutorial-list"),
                orderedCandidates.stream().map(Document::getId).toList());
    }

    @Test
    void treatsMethodDeclarationEvidenceAsCaseSensitive() {
        Document uppercaseMethodCandidate =
                javadocCandidate("uppercase-method", "A utility Of() method", "Object.html");
        Document lowercaseMethodCandidate =
                javadocCandidate("lowercase-method", "A utility of() method", "Object.html");

        List<Document> orderedCandidates = CitationCandidateRanker.orderForCitationQuery(
                "What does List.of return?", List.of(uppercaseMethodCandidate, lowercaseMethodCandidate));

        assertEquals(
                List.of("lowercase-method", "uppercase-method"),
                orderedCandidates.stream().map(Document::getId).toList());
    }

    @Test
    void retainsOriginalQdrantOrderWhenNoSelectorIsPresent() {
        Document firstCandidate = javadocCandidate("first", "Collection overview", "Collection.html");
        Document secondCandidate = javadocCandidate("second", "List overview", "List.html");

        List<Document> orderedCandidates = CitationCandidateRanker.orderForCitationQuery(
                "How do Java collections work?", List.of(firstCandidate, secondCandidate));

        assertEquals(
                List.of("first", "second"),
                orderedCandidates.stream().map(Document::getId).toList());
    }

    @Test
    void propagatesMalformedApiDocumentationUrlsInsteadOfSilentlyIgnoringTheirEvidence() {
        Document malformedCandidate = Document.builder()
                .id("malformed")
                .text("static <E> List<E> of(E element)")
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, "https://docs.example.test/java util/List.html")
                .metadata(QdrantPayloadFieldSchema.DOC_TYPE_FIELD, DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE)
                .build();

        assertThrows(
                IllegalArgumentException.class,
                () -> CitationCandidateRanker.orderForCitationQuery(
                        "What does List.of return?", List.of(malformedCandidate)));
    }

    private static Document javadocCandidate(String documentId, String documentText, String pageFilename) {
        return javadocCandidate(
                documentId, documentText, "java.util", pageFilename, DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE);
    }

    private static Document javadocCandidate(
            String documentId, String documentText, String packageName, String pageFilename, String documentType) {
        DocsSourceRegistry.JavaApiDocumentationSource representedJavaApiSource =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst();
        return Document.builder()
                .id(documentId)
                .text(documentText)
                .metadata(
                        QdrantPayloadFieldSchema.URL_FIELD,
                        representedJavaApiSource.remoteBaseUrl()
                                + "java.base/"
                                + packageName.replace('.', '/')
                                + "/"
                                + pageFilename)
                .metadata(QdrantPayloadFieldSchema.DOC_TYPE_FIELD, documentType)
                .build();
    }
}
