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
    void retainsOnlyTheExactSourceAnchorWithMatchingStoredMetadata() {
        Document oneArgumentCandidate = javaApiJavadocExactOverloadCandidate(
                "one-argument", "Returns one element.", javaUtilJavadocPage("List.html"), "List.html", "of(E)");
        Document threeArgumentCandidate = javaApiJavadocExactOverloadCandidate(
                "three-argument",
                "Returns three elements.",
                javaUtilJavadocPage("List.html"),
                "List.html",
                "of(E,E,E)");
        Document unanchoredTextCandidate = javaApiJavadocCandidate(
                "unanchored-text", "static <E> List<E> of(E first, E second)", javaUtilJavadocPage("List.html"));
        Document wrongTypePageCandidate = javaApiJavadocExactOverloadCandidate(
                "wrong-type-page", "Returns two elements.", javaUtilJavadocPage("List.html"), "Set.html", "of(E,E)");
        Document twoArgumentCandidate = javaApiJavadocExactOverloadCandidate(
                "two-argument", "Returns two elements.", javaUtilJavadocPage("List.html"), "List.html", "of(E,E)");

        List<Document> orderedCandidates = CitationCandidateRanker.orderForCitationQuery(
                "What does List.of(E, E) return?",
                List.of(
                        oneArgumentCandidate,
                        threeArgumentCandidate,
                        unanchoredTextCandidate,
                        wrongTypePageCandidate,
                        twoArgumentCandidate));

        assertEquals(
                List.of("two-argument"),
                orderedCandidates.stream().map(Document::getId).toList());
    }

    @Test
    void prioritizesTheZeroArgumentOverloadWhenTheQueryUsesEmptyInvocationParentheses() {
        Document oneArgumentCandidate = javaApiJavadocExactOverloadCandidate(
                "one-argument", "Returns one element.", javaUtilJavadocPage("List.html"), "List.html", "of(E)");
        Document zeroArgumentCandidate = javaApiJavadocExactOverloadCandidate(
                "zero-argument", "Returns zero elements.", javaUtilJavadocPage("List.html"), "List.html", "of()");

        List<Document> orderedCandidates = CitationCandidateRanker.orderForCitationQuery(
                "What does List.of() return?", List.of(oneArgumentCandidate, zeroArgumentCandidate));

        assertEquals(
                List.of("zero-argument"),
                orderedCandidates.stream().map(Document::getId).toList());
    }

    @Test
    void requiresStoredPackageMetadataForAQualifiedExactOverload() {
        Document sqlDateCandidate = javaApiJavadocExactOverloadCandidate(
                "sql-date", "SQL date text.", javadocPage("java.sql", "Date.html"), "Date.html", "toString()");
        Document utilDateCandidate = javaApiJavadocExactOverloadCandidate(
                "util-date", "Utility date text.", javaUtilJavadocPage("Date.html"), "Date.html", "toString()");

        List<Document> orderedCandidates = CitationCandidateRanker.orderForCitationQuery(
                "What does java.util.Date.toString() return?", List.of(sqlDateCandidate, utilDateCandidate));

        assertEquals(
                List.of("util-date"),
                orderedCandidates.stream().map(Document::getId).toList());
    }

    @Test
    void distinguishesSameArityStringValueOfOverloadsByTheirStoredAnchor() {
        Document charCandidate = javaApiJavadocExactOverloadCandidate(
                "char",
                "Returns one character.",
                javadocPage("java.lang", "String.html"),
                "String.html",
                "valueOf(char)");
        Document charArrayCandidate = javaApiJavadocExactOverloadCandidate(
                "char-array",
                "Returns a character array.",
                javadocPage("java.lang", "String.html"),
                "String.html",
                "valueOf(char[])");

        List<Document> orderedCandidates = CitationCandidateRanker.orderForCitationQuery(
                "What does String.valueOf(char[]) return?", List.of(charCandidate, charArrayCandidate));

        assertEquals(
                List.of("char-array"),
                orderedCandidates.stream().map(Document::getId).toList());
    }

    @Test
    void distinguishesAListVarargsOverloadByItsStoredAnchor() {
        Document oneArgumentCandidate = javaApiJavadocExactOverloadCandidate(
                "one-argument", "Returns one element.", javaUtilJavadocPage("List.html"), "List.html", "of(E)");
        Document varargsCandidate = javaApiJavadocExactOverloadCandidate(
                "varargs", "Returns many elements.", javaUtilJavadocPage("List.html"), "List.html", "of(E...)");

        List<Document> orderedCandidates = CitationCandidateRanker.orderForCitationQuery(
                "What does List.of(E...) return?", List.of(oneArgumentCandidate, varargsCandidate));

        assertEquals(
                List.of("varargs"),
                orderedCandidates.stream().map(Document::getId).toList());
    }

    @Test
    void retainsBroadOrderingForRuntimeValueArguments() {
        Document oneArgumentCandidate = javaApiJavadocCandidate(
                "one-argument", "static <E> List<E> of(E element)", javaUtilJavadocPage("List.html"));
        Document twoArgumentCandidate = javaApiJavadocCandidate(
                "two-argument", "static <E> List<E> of(E first, E second)", javaUtilJavadocPage("List.html"));

        List<Document> orderedCandidates = CitationCandidateRanker.orderForCitationQuery(
                "What does List.of(firstValue, secondValue) return?",
                List.of(oneArgumentCandidate, twoArgumentCandidate));

        assertEquals(
                List.of("one-argument", "two-argument"),
                orderedCandidates.stream().map(Document::getId).toList());
    }

    @Test
    void retainsFirstSelectorBroadRelevanceForMultiSelectorComparisons() {
        Document setCandidate = javaApiJavadocCandidate(
                "set", "static <E> Set<E> of(E first, E second)", javaUtilJavadocPage("Set.html"));
        Document listCandidate = javaApiJavadocCandidate(
                "list", "static <E> List<E> of(E first, E second)", javaUtilJavadocPage("List.html"));

        List<Document> orderedCandidates = CitationCandidateRanker.orderForCitationQuery(
                "Compare List.of(E, E) with Set.of(E, E)", List.of(setCandidate, listCandidate));

        assertEquals(
                List.of("list", "set"),
                orderedCandidates.stream().map(Document::getId).toList());
    }

    @Test
    void retainsBroadOrderingForIncompleteAndUnsupportedSignatures() {
        Document oneArgumentCandidate = javaApiJavadocCandidate(
                "one-argument", "static <E> List<E> of(E element)", javaUtilJavadocPage("List.html"));
        Document twoArgumentCandidate = javaApiJavadocCandidate(
                "two-argument", "static <E> List<E> of(E first, E second)", javaUtilJavadocPage("List.html"));
        List<Document> citationCandidates = List.of(oneArgumentCandidate, twoArgumentCandidate);

        List<Document> incompleteSignatureCandidates =
                CitationCandidateRanker.orderForCitationQuery("What does List.of(E, return?", citationCandidates);
        List<Document> genericSignatureCandidates =
                CitationCandidateRanker.orderForCitationQuery("What does List.of(List<E>) return?", citationCandidates);

        assertEquals(
                List.of("one-argument", "two-argument"),
                incompleteSignatureCandidates.stream().map(Document::getId).toList());
        assertEquals(
                List.of("one-argument", "two-argument"),
                genericSignatureCandidates.stream().map(Document::getId).toList());
    }

    @Test
    void preservesQdrantOverloadOrderWhenTheQueryOmitsInvocationParentheses() {
        Document threeArgumentCandidate = javaApiJavadocCandidate(
                "three-argument",
                "static <E> List<E> of(E first, E second, E third)",
                javaUtilJavadocPage("List.html"));
        Document oneArgumentCandidate = javaApiJavadocCandidate(
                "one-argument", "static <E> List<E> of(E element)", javaUtilJavadocPage("List.html"));

        List<Document> orderedCandidates = CitationCandidateRanker.orderForCitationQuery(
                "What does List.of return?", List.of(threeArgumentCandidate, oneArgumentCandidate));

        assertEquals(
                List.of("three-argument", "one-argument"),
                orderedCandidates.stream().map(Document::getId).toList());
    }

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
                "What does Java List.of return?", List.of(classFileCandidate, firstListCandidate, secondListCandidate));

        assertEquals(
                List.of("list-first", "list-second", "class-file"),
                orderedCandidates.stream().map(Document::getId).toList());
    }

    @Test
    void derivesCanonicalPackageFromSourceUrlsInsteadOfLegacyPackageMetadata() {
        Document classUseCandidate = javaApiJavadocCandidateWithPackageMetadata(
                "class-use",
                "static <E> List<E> of(E element)",
                javadocPage("java.util.class-use", "List.html"),
                "java.util");
        Document rootPageCandidate =
                javaApiRootJavadocCandidate("root-page", "static <E> List<E> of(E element)", "List.html", "java.util");
        Document canonicalListCandidate = javaApiJavadocCandidateWithPackageMetadata(
                "canonical-list",
                "static <E> List<E> of(E element)",
                javaUtilJavadocPage("List.html"),
                "java.base.java.util");

        List<Document> orderedCandidates = CitationCandidateRanker.orderForCitationQuery(
                "What does Java List.of return?",
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

    private static Document javaApiJavadocExactOverloadCandidate(
            String documentId,
            String documentText,
            JavadocPage javadocPage,
            String typePageFilename,
            String memberAnchor) {
        return Document.builder()
                .id(documentId)
                .text(documentText)
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, javaApiJavadocUrl(javadocPage))
                .metadata(QdrantPayloadFieldSchema.DOC_TYPE_FIELD, DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE)
                .metadata(QdrantPayloadFieldSchema.PACKAGE_FIELD, javadocPage.packageName())
                .metadata(QdrantPayloadFieldSchema.JAVA_API_TYPE_PAGE_FIELD, typePageFilename)
                .metadata(QdrantPayloadFieldSchema.ANCHOR_FIELD, memberAnchor)
                .build();
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

    private static Document javaApiRootJavadocCandidate(
            String documentId, String documentText, String filename, String candidatePackageName) {
        DocsSourceRegistry.JavaApiDocumentationSource representedJavaApiSource =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst();
        return Document.builder()
                .id(documentId)
                .text(documentText)
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, representedJavaApiSource.remoteBaseUrl() + filename)
                .metadata(QdrantPayloadFieldSchema.DOC_TYPE_FIELD, DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE)
                .metadata(QdrantPayloadFieldSchema.PACKAGE_FIELD, candidatePackageName)
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
