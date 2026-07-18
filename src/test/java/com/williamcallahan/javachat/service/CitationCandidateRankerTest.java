package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.williamcallahan.javachat.config.DocsSourceRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/** Verifies deterministic local ordering of sparse static-citation candidates. */
class CitationCandidateRankerTest {

    private static final String TUTORIAL_DOCUMENT_TYPE = "tutorial";

    @Test
    void prioritizesExactJavadocTypePagesOverHigherRankedMethodOnlyMatches() {
        Document classFileCandidate = javaApiJavadocCandidate(
                "class-file",
                "ClassFileFormatVersion factory of()",
                javaUtilJavadocPage("ClassFileFormatVersion.html"));
        Document firstListCandidate = javaApiJavadocCandidate(
                "list-first", "static <E> List<E> of(E element)", javaUtilJavadocPage("List.html"));
        Document secondListCandidate = javaApiJavadocCandidate(
                "list-second", "static <E> List<E> of(E first, E second)", javaUtilJavadocPage("List.html"));

        List<Document> orderedCandidates = CitationCandidateRanker.orderForCitationQuery(
                "What does Java List.of() return?",
                List.of(classFileCandidate, firstListCandidate, secondListCandidate));

        assertEquals(
                List.of("list-first", "list-second", "class-file"),
                orderedCandidates.stream().map(Document::getId).toList());
    }

    @Test
    void prioritizesCanonicalTypePagesOverClassUseAndRootPageCandidates() {
        Document classUseCandidate = javaApiJavadocCandidate(
                "class-use", "static <E> List<E> of(E element)", javadocPage("java.util.class-use", "List.html"));
        Document rootPageCandidate =
                javaApiRootJavadocCandidate("root-page", "static <E> List<E> of(E element)", "List.html");
        Document canonicalListCandidate = javaApiJavadocCandidate(
                "canonical-list", "static <E> List<E> of(E element)", javaUtilJavadocPage("List.html"));

        List<Document> orderedCandidates = CitationCandidateRanker.orderForCitationQuery(
                "What does Java List.of() return?",
                List.of(classUseCandidate, rootPageCandidate, canonicalListCandidate));

        assertEquals(
                List.of("canonical-list", "class-use", "root-page"),
                orderedCandidates.stream().map(Document::getId).toList());
    }

    @Test
    void recognizesUnparenthesizedTypeMethodSelectorsDuringCandidateOrdering() {
        Document classFileCandidate = javaApiJavadocCandidate(
                "class-file", "A utility map() method", javaUtilJavadocPage("ClassFileFormatVersion.html"));
        Document streamCandidate = javaApiJavadocCandidate(
                "stream", "<R> Stream<R> map(Function mapper)", javaUtilJavadocPage("Stream.html"));

        List<Document> orderedCandidates = CitationCandidateRanker.orderForCitationQuery(
                "Explain Stream.map in Java", List.of(classFileCandidate, streamCandidate));

        assertEquals(
                List.of("stream", "class-file"),
                orderedCandidates.stream().map(Document::getId).toList());
    }

    @Test
    void matchesTheDeclaredPackageWhenASelectorIsFullyQualified() {
        Document sqlDateCandidate =
                javaApiJavadocCandidate("sql-date", "String toString()", javadocPage("java.sql", "Date.html"));
        Document utilDateCandidate =
                javaApiJavadocCandidate("util-date", "String toString()", javaUtilJavadocPage("Date.html"));

        List<Document> orderedCandidates = CitationCandidateRanker.orderForCitationQuery(
                "What does java.util.Date.toString return?", List.of(sqlDateCandidate, utilDateCandidate));

        assertEquals(
                List.of("util-date", "sql-date"),
                orderedCandidates.stream().map(Document::getId).toList());
    }

    @Test
    void matchesQualifiedSelectorsByUrlWhenCandidatePackageMetadataIsAbsentOrWrong() {
        Document incorrectSqlDateCandidate = javaApiJavadocCandidateWithPackageMetadata(
                "sql-date", "String toString()", javadocPage("java.sql", "Date.html"), "java.util");
        Document correctUtilDateCandidate = javaApiJavadocCandidateWithoutPackageMetadata(
                "util-date", "String toString()", javaUtilJavadocPage("Date.html"));

        List<Document> orderedCandidates = CitationCandidateRanker.orderForCitationQuery(
                "What does java.util.Date.toString return?",
                List.of(incorrectSqlDateCandidate, correctUtilDateCandidate));

        assertEquals(
                List.of("util-date", "sql-date"),
                orderedCandidates.stream().map(Document::getId).toList());
    }

    @Test
    void excludesNonApiDocumentationFromSelectorEvidence() {
        Document apiMethodOnlyCandidate =
                javaApiJavadocCandidate("api-method", "A utility of() method", javadocPage("java.lang", "Object.html"));
        Document tutorialListCandidate = tutorialJavadocCandidate(
                "tutorial-list", "static <E> List<E> of(E element)", javaUtilJavadocPage("List.html"));

        List<Document> orderedCandidates = CitationCandidateRanker.orderForCitationQuery(
                "What does List.of return?", List.of(apiMethodOnlyCandidate, tutorialListCandidate));

        assertEquals(
                List.of("api-method", "tutorial-list"),
                orderedCandidates.stream().map(Document::getId).toList());
    }

    @Test
    void treatsMethodDeclarationEvidenceAsCaseSensitive() {
        Document uppercaseMethodCandidate = javaApiJavadocCandidate(
                "uppercase-method", "A utility Of() method", javaUtilJavadocPage("Object.html"));
        Document lowercaseMethodCandidate = javaApiJavadocCandidate(
                "lowercase-method", "A utility of() method", javaUtilJavadocPage("Object.html"));

        List<Document> orderedCandidates = CitationCandidateRanker.orderForCitationQuery(
                "What does List.of return?", List.of(uppercaseMethodCandidate, lowercaseMethodCandidate));

        assertEquals(
                List.of("lowercase-method", "uppercase-method"),
                orderedCandidates.stream().map(Document::getId).toList());
    }

    @Test
    void retainsOriginalQdrantOrderWhenNoSelectorIsPresent() {
        Document firstCandidate =
                javaApiJavadocCandidate("first", "Collection overview", javaUtilJavadocPage("Collection.html"));
        Document secondCandidate = javaApiJavadocCandidate("second", "List overview", javaUtilJavadocPage("List.html"));

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
                .metadata(QdrantPayloadFieldSchema.PACKAGE_FIELD, "java.util")
                .build();

        assertThrows(
                IllegalArgumentException.class,
                () -> CitationCandidateRanker.orderForCitationQuery(
                        "What does List.of return?", List.of(malformedCandidate)));
    }

    private static Document javaApiJavadocCandidate(String documentId, String documentText, JavadocPage javadocPage) {
        return javadocCandidateWithDocumentType(
                documentId, documentText, javadocPage, DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE);
    }

    private static Document javaApiJavadocCandidateWithPackageMetadata(
            String documentId, String documentText, JavadocPage javadocPage, String candidatePackageName) {
        return Document.builder()
                .id(documentId)
                .text(documentText)
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, javaApiJavadocUrl(javadocPage))
                .metadata(QdrantPayloadFieldSchema.DOC_TYPE_FIELD, DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE)
                .metadata(QdrantPayloadFieldSchema.PACKAGE_FIELD, candidatePackageName)
                .build();
    }

    private static Document javaApiJavadocCandidateWithoutPackageMetadata(
            String documentId, String documentText, JavadocPage javadocPage) {
        return Document.builder()
                .id(documentId)
                .text(documentText)
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, javaApiJavadocUrl(javadocPage))
                .metadata(QdrantPayloadFieldSchema.DOC_TYPE_FIELD, DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE)
                .build();
    }

    private static Document tutorialJavadocCandidate(String documentId, String documentText, JavadocPage javadocPage) {
        return javadocCandidateWithDocumentType(documentId, documentText, javadocPage, TUTORIAL_DOCUMENT_TYPE);
    }

    private static Document javadocCandidateWithDocumentType(
            String documentId, String documentText, JavadocPage javadocPage, String documentType) {
        return Document.builder()
                .id(documentId)
                .text(documentText)
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, javaApiJavadocUrl(javadocPage))
                .metadata(QdrantPayloadFieldSchema.DOC_TYPE_FIELD, documentType)
                .metadata(QdrantPayloadFieldSchema.PACKAGE_FIELD, javadocPage.packageName())
                .build();
    }

    private static Document javaApiRootJavadocCandidate(String documentId, String documentText, String filename) {
        DocsSourceRegistry.JavaApiDocumentationSource representedJavaApiSource =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst();
        return Document.builder()
                .id(documentId)
                .text(documentText)
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, representedJavaApiSource.remoteBaseUrl() + filename)
                .metadata(QdrantPayloadFieldSchema.DOC_TYPE_FIELD, DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE)
                .metadata(QdrantPayloadFieldSchema.PACKAGE_FIELD, "")
                .build();
    }

    private static String javaApiJavadocUrl(JavadocPage javadocPage) {
        DocsSourceRegistry.JavaApiDocumentationSource representedJavaApiSource =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst();
        return representedJavaApiSource.remoteBaseUrl()
                + "java.base/"
                + javadocPage.packageName().replace('.', '/')
                + "/"
                + javadocPage.filename();
    }

    private static JavadocPage javaUtilJavadocPage(String filename) {
        return javadocPage("java.util", filename);
    }

    private static JavadocPage javadocPage(String packageName, String filename) {
        return new JavadocPage(packageName, filename);
    }

    private record JavadocPage(String packageName, String filename) {}
}
