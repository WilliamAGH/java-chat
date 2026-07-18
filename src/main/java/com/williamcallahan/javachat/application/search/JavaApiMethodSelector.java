package com.williamcallahan.javachat.application.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Identifies an explicitly named Java API type method within a natural-language query.
 *
 * <p>The selector owns the relationship between a query spelling such as {@code List.of} and the
 * Javadoc page filename {@code List.html}. Sparse citation retrieval uses its expanded terms to
 * bridge punctuation tokenization, while citation ranking uses the same selector to recognize the
 * matching Javadoc type page. A qualified selector also owns its package path so {@code
 * java.util.Date.toString} cannot match {@code java.sql.Date}.</p>
 *
 * @param packageName optional declaring package name, or empty for an unqualified selector
 * @param typePageName Javadoc type page name without the {@code .html} suffix
 * @param methodName Java method name named by the query
 */
public record JavaApiMethodSelector(String packageName, String typePageName, String methodName) {

    private static final String JAVADOC_PAGE_SUFFIX = ".html";
    private static final int MINIMUM_TYPE_METHOD_SEGMENT_COUNT = 2;
    private static final Set<String> NON_METHOD_TERMINALS = Set.of("class", "super", "this", "java", "html");

    /**
     * Enforces a concrete Java API type and method spelling.
     */
    public JavaApiMethodSelector {
        packageName = packageName == null ? "" : packageName.trim();
        typePageName = requireNonBlank(typePageName, "typePageName");
        methodName = requireNonBlank(methodName, "methodName");
    }

    /**
     * Extracts the first explicit {@code Type.method} selector from a query.
     *
     * <p>Invocation parentheses are optional because learners commonly ask both {@code List.of()}
     * and {@code Explain Stream.map}. Fully qualified type names and Javadoc nested-type page names
     * are retained without re-parsing them elsewhere.</p>
     *
     * @param query learner query text
     * @return first explicit Java API method selector, or empty when the query names none
     */
    public static Optional<JavaApiMethodSelector> fromQuery(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }

        int queryLength = query.length();
        for (int queryIndex = 0; queryIndex < queryLength; queryIndex++) {
            if (!isIdentifierStartAt(query, queryIndex) || hasIdentifierPrefix(query, queryIndex)) {
                continue;
            }
            ParsedQualifiedName parsedQualifiedName = parseQualifiedName(query, queryIndex);
            Optional<JavaApiMethodSelector> candidateSelector = fromQualifiedName(parsedQualifiedName.segments());
            if (candidateSelector.isPresent()) {
                return candidateSelector;
            }
            queryIndex = parsedQualifiedName.endIndex() - 1;
        }
        return Optional.empty();
    }

    /**
     * Adds component terms for an explicit selector while retaining its punctuated spelling.
     *
     * <p>Lucene's {@code StandardAnalyzer} keeps {@code List.of} as one lexical term in the
     * original query. Adding only {@code List} (or {@code Map.Entry}) recalls its declaring page
     * without adding noisy common method terms such as {@code of}. Document-vector encoding remains
     * unchanged.</p>
     *
     * @param citationQuery original sparse citation query
     * @return original query plus selector component terms when a selector is present
     */
    public static String expandForSparseCitationQuery(String citationQuery) {
        Objects.requireNonNull(citationQuery, "citationQuery");
        return fromQuery(citationQuery)
                .map(selector -> citationQuery + " " + selector.sparseQueryTerms())
                .orElse(citationQuery);
    }

    /**
     * Returns the exact Javadoc filename expected for this selector's declaring type.
     *
     * @return Javadoc page filename including its extension
     */
    public String typePageFileName() {
        return typePageName + JAVADOC_PAGE_SUFFIX;
    }

    /**
     * Determines whether a Javadoc URL path identifies this selector's declaring type.
     *
     * <p>Unqualified selectors match the type filename in any package. Qualified selectors require
     * the exact package path immediately before that filename, preventing same-named JDK types in
     * different packages from receiving the same citation priority.</p>
     *
     * @param javadocPath decoded Javadoc URL path
     * @return true when the path names this selector's declaring type
     */
    public boolean matchesJavadocPath(String javadocPath) {
        Objects.requireNonNull(javadocPath, "javadocPath");
        int filenameStartIndex = javadocPath.lastIndexOf('/') + 1;
        String candidateFilename = javadocPath.substring(filenameStartIndex);
        if (!typePageFileName().equals(candidateFilename)) {
            return false;
        }
        if (packageName.isBlank()) {
            return true;
        }
        String qualifiedPagePathSuffix = "/" + packageName.replace('.', '/') + "/" + typePageFileName();
        return javadocPath.endsWith(qualifiedPagePathSuffix);
    }

    /**
     * Returns the exact declaring-type syntax for sparse query expansion.
     *
     * @return declaring type syntax, including nested-type delimiters when present
     */
    public String sparseQueryTerms() {
        return typePageName;
    }

    private static ParsedQualifiedName parseQualifiedName(String query, int startIndex) {
        List<String> segments = new ArrayList<>();
        int currentIndex = startIndex;
        while (currentIndex < query.length()) {
            int segmentEndIndex = readIdentifierEnd(query, currentIndex);
            segments.add(query.substring(currentIndex, segmentEndIndex));
            if (segmentEndIndex >= query.length() || query.charAt(segmentEndIndex) != '.') {
                return new ParsedQualifiedName(segments, segmentEndIndex);
            }
            int nextSegmentStartIndex = segmentEndIndex + 1;
            if (!isIdentifierStartAt(query, nextSegmentStartIndex)) {
                return new ParsedQualifiedName(segments, segmentEndIndex);
            }
            currentIndex = nextSegmentStartIndex;
        }
        return new ParsedQualifiedName(segments, currentIndex);
    }

    private static Optional<JavaApiMethodSelector> fromQualifiedName(List<String> segments) {
        if (segments.size() < MINIMUM_TYPE_METHOD_SEGMENT_COUNT) {
            return Optional.empty();
        }

        int methodSegmentIndex = segments.size() - 1;
        String candidateMethodName = segments.get(methodSegmentIndex);
        if (!Character.isLowerCase(candidateMethodName.charAt(0))
                || NON_METHOD_TERMINALS.contains(candidateMethodName)) {
            return Optional.empty();
        }

        int firstTypeSegmentIndex = firstTypeSegmentIndex(segments, methodSegmentIndex);
        if (firstTypeSegmentIndex < 0 || hasNonTypeSegment(segments, firstTypeSegmentIndex, methodSegmentIndex)) {
            return Optional.empty();
        }

        String candidatePackageName = String.join(".", segments.subList(0, firstTypeSegmentIndex));
        String candidateTypePageName = String.join(".", segments.subList(firstTypeSegmentIndex, methodSegmentIndex));
        return Optional.of(new JavaApiMethodSelector(candidatePackageName, candidateTypePageName, candidateMethodName));
    }

    private static int firstTypeSegmentIndex(List<String> segments, int methodSegmentIndex) {
        for (int segmentIndex = 0; segmentIndex < methodSegmentIndex; segmentIndex++) {
            if (Character.isUpperCase(segments.get(segmentIndex).charAt(0))) {
                return segmentIndex;
            }
        }
        return -1;
    }

    private static boolean hasNonTypeSegment(List<String> segments, int firstTypeSegmentIndex, int methodSegmentIndex) {
        for (int segmentIndex = firstTypeSegmentIndex; segmentIndex < methodSegmentIndex; segmentIndex++) {
            if (!Character.isUpperCase(segments.get(segmentIndex).charAt(0))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIdentifierStartAt(String query, int queryIndex) {
        return queryIndex >= 0
                && queryIndex < query.length()
                && Character.isJavaIdentifierStart(query.charAt(queryIndex));
    }

    private static boolean hasIdentifierPrefix(String query, int queryIndex) {
        return queryIndex > 0 && Character.isJavaIdentifierPart(query.charAt(queryIndex - 1));
    }

    private static int readIdentifierEnd(String query, int startIndex) {
        int currentIndex = startIndex + 1;
        while (currentIndex < query.length() && Character.isJavaIdentifierPart(query.charAt(currentIndex))) {
            currentIndex++;
        }
        return currentIndex;
    }

    private static String requireNonBlank(String text, String fieldName) {
        String nonNullText = Objects.requireNonNull(text, fieldName);
        if (nonNullText.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return nonNullText.trim();
    }

    private record ParsedQualifiedName(List<String> segments, int endIndex) {
        private ParsedQualifiedName {
            segments = List.copyOf(segments);
        }
    }
}
