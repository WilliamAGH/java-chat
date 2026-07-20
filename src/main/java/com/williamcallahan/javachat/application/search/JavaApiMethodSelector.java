package com.williamcallahan.javachat.application.search;

import com.williamcallahan.javachat.domain.javaapi.JavaPackageName;
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
 * java.util.Date.toString} cannot match {@code java.sql.Date}. A selector exposes an exact source
 * anchor only when the learner supplied one unambiguous Java type signature.</p>
 */
public final class JavaApiMethodSelector {

    private static final String JAVADOC_PAGE_SUFFIX = ".html";
    private static final String VIRTUAL_THREAD_FACTORY_METHOD = "ofVirtual";
    private static final String VIRTUAL_THREAD_FACTORY_ANCHOR = "ofVirtual()";
    private static final String VIRTUAL_THREAD_START_CHAIN = "().start(Runnable)";
    private static final String QUALIFIED_VIRTUAL_THREAD_START_CHAIN = "().start(java.lang.Runnable)";
    private static final String THREAD_TYPE_PAGE = "Thread";
    private static final String THREAD_BUILDER_TYPE_PAGE = "Thread.Builder";
    private static final String THREAD_BUILDER_START_METHOD = "start";
    private static final String THREAD_BUILDER_START_PARAMETER_CLAUSE = "(java.lang.Runnable)";
    private static final String JAVA_LANG_PACKAGE = "java.lang";
    private static final int MINIMUM_TYPE_METHOD_SEGMENT_COUNT = 2;
    private static final Set<String> NON_METHOD_TERMINALS = Set.of("class", "super", "this", "java", "html");
    private final String packageName;
    private final String typePageName;
    private final String methodName;
    private final JavaInvocationSignature invocationSignature;

    /**
     * Creates a selector without an exact invocation signature.
     *
     * <p>Direct construction names a method independently of a learner query, so it must not imply
     * an overload choice.</p>
     *
     * @param packageName optional declaring package name, or empty for an unqualified selector
     * @param typePageName Javadoc type page name without the {@code .html} suffix
     * @param methodName Java method name
     */
    public JavaApiMethodSelector(String packageName, String typePageName, String methodName) {
        this(packageName, typePageName, methodName, JavaInvocationSignature.unavailable());
    }

    private JavaApiMethodSelector(
            String packageName, String typePageName, String methodName, JavaInvocationSignature invocationSignature) {
        this.packageName = packageName == null ? "" : packageName.trim();
        this.typePageName = requireNonBlank(typePageName, "typePageName");
        this.methodName = requireNonBlank(methodName, "methodName");
        this.invocationSignature = Objects.requireNonNull(invocationSignature, "invocationSignature");
    }

    /**
     * Extracts the first explicit {@code Type.method} selector from a query.
     *
     * <p>Invocation parentheses are optional because learners commonly ask both {@code List.of()}
     * and {@code Explain Stream.map}. Fully qualified type names and Javadoc nested-type page names
     * are retained without re-parsing them elsewhere. This relevance-only selector never exposes an
     * overload anchor; use {@link #uniqueExactOverloadFromQuery(String)} for that stricter path.</p>
     *
     * @param query learner query text
     * @return first explicit Java API method selector, or empty when the query names none
     */
    public static Optional<JavaApiMethodSelector> fromQuery(String query) {
        return selectorOccurrences(query).stream()
                .map(SelectorOccurrence::selector)
                .findFirst();
    }

    /**
     * Extracts an exact overload selector only when one selector has an unambiguous type signature.
     *
     * <p>Comparisons and value-expression invocations retain broad relevance ordering because this
     * method refuses to infer a Javadoc anchor from either form.</p>
     *
     * @param query learner query text
     * @return sole selector with an exact source-anchor lookup key, or empty when none is safe
     */
    public static Optional<JavaApiMethodSelector> uniqueExactOverloadFromQuery(String query) {
        List<SelectorOccurrence> selectorOccurrences = selectorOccurrences(query);
        if (selectorOccurrences.size() != 1) {
            return Optional.empty();
        }
        SelectorOccurrence selectorOccurrence = selectorOccurrences.getFirst();
        JavaInvocationSignature invocationSignature =
                JavaInvocationSignature.afterMethodName(query, selectorOccurrence.methodEndIndex());
        if (invocationSignature.isExact()
                && hasChainedMethodInvocationAfterInvocation(query, selectorOccurrence.methodEndIndex())) {
            return mappedJavadocDeclarationForKnownChain(query, selectorOccurrence, invocationSignature);
        }
        JavaApiMethodSelector exactSelector =
                selectorOccurrence.selector().withInvocationSignature(invocationSignature);
        return exactSelector.exactOverloadAnchor().isPresent() ? Optional.of(exactSelector) : Optional.empty();
    }

    private static Optional<JavaApiMethodSelector> mappedJavadocDeclarationForKnownChain(
            String query, SelectorOccurrence selectorOccurrence, JavaInvocationSignature invocationSignature) {
        JavaApiMethodSelector receiverSelector = selectorOccurrence.selector();
        if ((!receiverSelector.packageName().isBlank() && !JAVA_LANG_PACKAGE.equals(receiverSelector.packageName()))
                || !THREAD_TYPE_PAGE.equals(receiverSelector.typePageName())
                || !VIRTUAL_THREAD_FACTORY_METHOD.equals(receiverSelector.methodName())
                || invocationSignature
                        .anchorFor(VIRTUAL_THREAD_FACTORY_METHOD)
                        .filter(VIRTUAL_THREAD_FACTORY_ANCHOR::equals)
                        .isEmpty()) {
            return Optional.empty();
        }
        String compactInvocationSuffix = removeWhitespace(query.substring(selectorOccurrence.methodEndIndex()));
        if (!compactInvocationSuffix.startsWith(VIRTUAL_THREAD_START_CHAIN)
                && !compactInvocationSuffix.startsWith(QUALIFIED_VIRTUAL_THREAD_START_CHAIN)) {
            return Optional.empty();
        }
        return Optional.of(new JavaApiMethodSelector(
                JAVA_LANG_PACKAGE,
                THREAD_BUILDER_TYPE_PAGE,
                THREAD_BUILDER_START_METHOD,
                new JavaInvocationSignature(true, THREAD_BUILDER_START_PARAMETER_CLAUSE)));
    }

    private static String removeWhitespace(String querySuffix) {
        StringBuilder compactQuerySuffix = new StringBuilder(querySuffix.length());
        for (int currentIndex = 0; currentIndex < querySuffix.length(); currentIndex++) {
            char currentCharacter = querySuffix.charAt(currentIndex);
            if (!Character.isWhitespace(currentCharacter)) {
                compactQuerySuffix.append(currentCharacter);
            }
        }
        return compactQuerySuffix.toString();
    }

    private static boolean hasChainedMethodInvocationAfterInvocation(String query, int methodEndIndex) {
        int openingParenthesisIndex = skipWhitespace(query, methodEndIndex);
        int invocationDepth = 0;
        for (int currentIndex = openingParenthesisIndex; currentIndex < query.length(); currentIndex++) {
            char currentCharacter = query.charAt(currentIndex);
            if (currentCharacter == '(') {
                invocationDepth++;
            } else if (currentCharacter == ')') {
                invocationDepth--;
                if (invocationDepth == 0) {
                    int memberAccessIndex = skipWhitespace(query, currentIndex + 1);
                    if (memberAccessIndex >= query.length() || query.charAt(memberAccessIndex) != '.') {
                        return false;
                    }
                    int chainedMethodStartIndex = skipWhitespace(query, memberAccessIndex + 1);
                    if (chainedMethodStartIndex < query.length() && query.charAt(chainedMethodStartIndex) == '<') {
                        chainedMethodStartIndex = skipExplicitMethodTypeArguments(query, chainedMethodStartIndex);
                    }
                    if (!isIdentifierStartAt(query, chainedMethodStartIndex)) {
                        return false;
                    }
                    int chainedMethodEndIndex = readIdentifierEnd(query, chainedMethodStartIndex);
                    int chainedInvocationIndex = skipWhitespace(query, chainedMethodEndIndex);
                    return chainedInvocationIndex < query.length() && query.charAt(chainedInvocationIndex) == '(';
                }
            }
        }
        return false;
    }

    private static int skipExplicitMethodTypeArguments(String query, int openingTypeArgumentIndex) {
        int typeArgumentDepth = 0;
        for (int currentIndex = openingTypeArgumentIndex; currentIndex < query.length(); currentIndex++) {
            char currentCharacter = query.charAt(currentIndex);
            if (currentCharacter == '<') {
                typeArgumentDepth++;
            } else if (currentCharacter == '>') {
                typeArgumentDepth--;
                if (typeArgumentDepth == 0) {
                    return skipWhitespace(query, currentIndex + 1);
                }
            }
        }
        return query.length();
    }

    private static List<SelectorOccurrence> selectorOccurrences(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        List<SelectorOccurrence> selectorOccurrences = new ArrayList<>();
        int queryLength = query.length();
        for (int queryIndex = 0; queryIndex < queryLength; queryIndex++) {
            if (!isIdentifierStartAt(query, queryIndex) || hasIdentifierPrefix(query, queryIndex)) {
                continue;
            }
            ParsedQualifiedName parsedQualifiedName = parseQualifiedName(query, queryIndex);
            fromQualifiedName(parsedQualifiedName.segments())
                    .ifPresent(selector ->
                            selectorOccurrences.add(new SelectorOccurrence(selector, parsedQualifiedName.endIndex())));
            queryIndex = parsedQualifiedName.endIndex() - 1;
        }
        return List.copyOf(selectorOccurrences);
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
     * Returns the optional declaring package named by the learner.
     *
     * @return package name, or an empty string for an unqualified selector
     */
    public String packageName() {
        return packageName;
    }

    /**
     * Returns the Javadoc type-page name without its filename suffix.
     *
     * @return declaring type-page name
     */
    public String typePageName() {
        return typePageName;
    }

    /**
     * Returns the exact Java method name named by the learner.
     *
     * @return method name
     */
    public String methodName() {
        return methodName;
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
     * <p>Qualified selectors use their query package and ignore the candidate package. Unqualified
     * selectors require a canonical Java package derived from the candidate source URL, which
     * keeps package-relative API pages from being mistaken for canonical type pages.</p>
     *
     * @param javadocPath decoded Javadoc URL path
     * @param candidatePackageName package derived from the candidate source URL, or {@code null}
     *     when absent
     * @return true when the path names this selector's declaring type in its expected package
     */
    public boolean matchesJavadocPath(String javadocPath, String candidatePackageName) {
        Objects.requireNonNull(javadocPath, "javadocPath");
        return expectedPackageName(candidatePackageName)
                .map(expectedPackageName -> matchesPackageTypePath(javadocPath, expectedPackageName))
                .orElse(false);
    }

    /**
     * Returns the exact declaring-type syntax for sparse query expansion.
     *
     * @return declaring type syntax, including nested-type delimiters when present
     */
    public String sparseQueryTerms() {
        return typePageName;
    }

    /**
     * Returns the source-anchor lookup key that the learner wrote without ambiguity.
     *
     * @return exact method anchor such as {@code of(E,E)}, or empty when no safe key exists
     */
    public Optional<String> exactOverloadAnchor() {
        return invocationSignature.anchorFor(methodName);
    }

    private JavaApiMethodSelector withInvocationSignature(JavaInvocationSignature invocationSignature) {
        return new JavaApiMethodSelector(packageName, typePageName, methodName, invocationSignature);
    }

    private Optional<JavaPackageName> expectedPackageName(String candidatePackageName) {
        if (!packageName.isBlank()) {
            return JavaPackageName.from(packageName);
        }
        return JavaPackageName.from(candidatePackageName);
    }

    private boolean matchesPackageTypePath(String javadocPath, JavaPackageName expectedPackageName) {
        String expectedPagePath = "/" + expectedPackageName.javadocPath() + "/" + typePageFileName();
        return javadocPath.endsWith(expectedPagePath);
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

    private static int skipWhitespace(String query, int startIndex) {
        int currentIndex = startIndex;
        while (currentIndex < query.length() && Character.isWhitespace(query.charAt(currentIndex))) {
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

    private record SelectorOccurrence(JavaApiMethodSelector selector, int methodEndIndex) {}
}
