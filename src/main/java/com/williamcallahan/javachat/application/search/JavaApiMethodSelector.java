package com.williamcallahan.javachat.application.search;

import com.williamcallahan.javachat.domain.javaapi.JavaPackageName;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
    private static final String THREAD_TYPE_PAGE = "Thread";
    private static final String THREAD_BUILDER_TYPE_PAGE = "Thread.Builder";
    private static final String THREAD_BUILDER_START_METHOD = "start";
    private static final String THREAD_BUILDER_START_PARAMETER_CLAUSE = "(java.lang.Runnable)";
    private static final String JAVA_LANG_PACKAGE = "java.lang";
    private static final int MINIMUM_TYPE_METHOD_SEGMENT_COUNT = 2;
    private static final Set<String> NON_METHOD_TERMINALS = Set.of("class", "super", "this", "java", "html", "new");
    private static final ConcurrentMap<PlatformMemberLookup, Optional<String>> JAVA_PLATFORM_MEMBER_PACKAGE_CACHE =
            new ConcurrentHashMap<>();
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
     * Extracts one member selector only when the query names exactly one Java API member.
     *
     * <p>Deterministic documentation retrieval uses this stricter form so comparison questions do
     * not discard evidence for later selectors.</p>
     *
     * @param query learner query text
     * @return sole Java API member selector, or empty when none or multiple are present
     */
    public static Optional<JavaApiMethodSelector> uniqueMemberFromQuery(String query) {
        List<SelectorOccurrence> selectorOccurrences = selectorOccurrences(query);
        if (selectorOccurrences.size() != 1) {
            return Optional.empty();
        }
        SelectorOccurrence selectorOccurrence = selectorOccurrences.getFirst();
        if (selectorOccurrence.supportsInvocationSignature()
                && hasInvocationAt(query, selectorOccurrence.methodEndIndex())) {
            if (hasUnsupportedMemberFamilyInvocation(query, selectorOccurrence.methodEndIndex())
                    || chainedMethodStartIndexAfterInvocation(query, selectorOccurrence.methodEndIndex()) >= 0) {
                return Optional.empty();
            }
        }
        return Optional.of(selectorOccurrence.selector());
    }

    /**
     * Extracts one member only when the query explicitly identifies the Java platform API.
     *
     * <p>A qualified or unqualified selector is authoritative only when its top-level type resolves
     * from an exported package in a Java platform module. Package-name prefixes, general Java or
     * Javadoc prose, and parameter syntax cannot make a third-party type pass that platform-owned
     * resolution check.</p>
     *
     * @param query learner query text
     * @return sole explicitly Java-platform member selector, or empty otherwise
     */
    public static Optional<JavaApiMethodSelector> uniqueExplicitJavaApiMemberFromQuery(String query) {
        Optional<JavaApiMethodSelector> exactSelector = uniqueExactOverloadFromQuery(query);
        Optional<JavaApiMethodSelector> exactPlatformSelector =
                exactSelector.flatMap(JavaApiMethodSelector::withResolvedJavaPlatformPackage);
        if (exactPlatformSelector.isPresent()) {
            return exactPlatformSelector;
        }
        return uniqueMemberFromQuery(query).flatMap(JavaApiMethodSelector::withResolvedJavaPlatformPackage);
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
        if (!selectorOccurrence.supportsInvocationSignature()) {
            return Optional.empty();
        }
        JavaInvocationSignature invocationSignature =
                JavaInvocationSignature.afterMethodName(query, selectorOccurrence.methodEndIndex());
        int chainedMethodStartIndex =
                chainedMethodStartIndexAfterInvocation(query, selectorOccurrence.methodEndIndex());
        if (invocationSignature.isExact() && chainedMethodStartIndex >= 0) {
            return mappedJavadocDeclarationForKnownChain(
                    query, selectorOccurrence, invocationSignature, chainedMethodStartIndex);
        }
        JavaApiMethodSelector exactSelector =
                selectorOccurrence.selector().withInvocationSignature(invocationSignature);
        return exactSelector.exactOverloadAnchor().isPresent() ? Optional.of(exactSelector) : Optional.empty();
    }

    private static Optional<JavaApiMethodSelector> mappedJavadocDeclarationForKnownChain(
            String query,
            SelectorOccurrence selectorOccurrence,
            JavaInvocationSignature invocationSignature,
            int chainedMethodStartIndex) {
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
        int chainedMethodEndIndex = readIdentifierEnd(query, chainedMethodStartIndex);
        String chainedMethodName = query.substring(chainedMethodStartIndex, chainedMethodEndIndex);
        JavaInvocationSignature chainedInvocationSignature =
                JavaInvocationSignature.afterMethodName(query, chainedMethodEndIndex);
        boolean recognizedStartSignature = hasSingleUnqualifiedRunnableParameter(query, chainedMethodEndIndex)
                || chainedInvocationSignature
                        .anchorFor(chainedMethodName)
                        .filter("start(java.lang.Runnable)"::equals)
                        .isPresent();
        if (!THREAD_BUILDER_START_METHOD.equals(chainedMethodName) || !recognizedStartSignature) {
            return Optional.empty();
        }
        return Optional.of(new JavaApiMethodSelector(
                JAVA_LANG_PACKAGE,
                THREAD_BUILDER_TYPE_PAGE,
                THREAD_BUILDER_START_METHOD,
                new JavaInvocationSignature(true, THREAD_BUILDER_START_PARAMETER_CLAUSE)));
    }

    private static boolean hasSingleUnqualifiedRunnableParameter(String query, int methodEndIndex) {
        int openingParenthesisIndex = skipWhitespace(query, methodEndIndex);
        if (openingParenthesisIndex >= query.length() || query.charAt(openingParenthesisIndex) != '(') {
            return false;
        }
        int parameterStartIndex = skipWhitespace(query, openingParenthesisIndex + 1);
        if (!isIdentifierStartAt(query, parameterStartIndex)) {
            return false;
        }
        int parameterEndIndex = readIdentifierEnd(query, parameterStartIndex);
        if (!"Runnable".equals(query.substring(parameterStartIndex, parameterEndIndex))) {
            return false;
        }
        int closingParenthesisIndex = skipWhitespace(query, parameterEndIndex);
        return closingParenthesisIndex < query.length() && query.charAt(closingParenthesisIndex) == ')';
    }

    private static int chainedMethodStartIndexAfterInvocation(String query, int methodEndIndex) {
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
                        return -1;
                    }
                    int chainedMethodStartIndex = skipWhitespace(query, memberAccessIndex + 1);
                    if (chainedMethodStartIndex < query.length() && query.charAt(chainedMethodStartIndex) == '<') {
                        chainedMethodStartIndex = skipExplicitMethodTypeArguments(query, chainedMethodStartIndex);
                    }
                    if (!isIdentifierStartAt(query, chainedMethodStartIndex)) {
                        return -1;
                    }
                    int chainedMethodEndIndex = readIdentifierEnd(query, chainedMethodStartIndex);
                    int chainedInvocationIndex = skipWhitespace(query, chainedMethodEndIndex);
                    if (chainedInvocationIndex < query.length() && query.charAt(chainedInvocationIndex) == '(') {
                        return chainedMethodStartIndex;
                    }
                    return Character.isLowerCase(query.charAt(chainedMethodStartIndex)) ? chainedMethodStartIndex : -1;
                }
            }
        }
        return -1;
    }

    private static boolean hasInvocationAt(String query, int methodEndIndex) {
        int openingParenthesisIndex = skipWhitespace(query, methodEndIndex);
        return openingParenthesisIndex < query.length() && query.charAt(openingParenthesisIndex) == '(';
    }

    private static boolean hasUnsupportedMemberFamilyInvocation(String query, int methodEndIndex) {
        int openingParenthesisIndex = skipWhitespace(query, methodEndIndex);
        int invocationDepth = 0;
        for (int currentIndex = openingParenthesisIndex; currentIndex < query.length(); currentIndex++) {
            char currentCharacter = query.charAt(currentIndex);
            if (currentCharacter == '<' || currentCharacter == '>') {
                return true;
            }
            if (currentCharacter == '(') {
                invocationDepth++;
            } else if (currentCharacter == ')') {
                invocationDepth--;
                if (invocationDepth == 0) {
                    return false;
                }
            }
        }
        return true;
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
                    .ifPresent(selector -> selectorOccurrences.add(new SelectorOccurrence(
                            selector, parsedQualifiedName.endIndex(), !parsedQualifiedName.methodReference())));
            queryIndex = parsedQualifiedName.endIndex() - 1;
        }
        return List.copyOf(selectorOccurrences);
    }

    /**
     * Builds a sparse citation query around an explicit selector.
     *
     * <p>A uniquely validated Java platform member uses only its declaring type and method terms.
     * The corresponding official-document query is constrained by type-page metadata, so unrelated
     * answer-formatting prose cannot dilute member recall and common method names cannot introduce
     * cross-type results. Unverified and multi-member queries retain their original text while
     * receiving component terms for broad relevance. Document-vector encoding remains unchanged.</p>
     *
     * @param citationQuery original citation query
     * @return selector-focused query for a validated Java member, otherwise the original query with
     *     broad selector component terms
     */
    public static String sparseCitationQuery(String citationQuery) {
        Objects.requireNonNull(citationQuery, "citationQuery");
        return uniqueExplicitJavaApiMemberFromQuery(citationQuery)
                .map(JavaApiMethodSelector::sparseQueryTerms)
                .orElseGet(() -> fromQuery(citationQuery)
                        .map(selector -> citationQuery + " " + selector.sparseQueryTerms())
                        .orElse(citationQuery));
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
        return typePageName + " " + methodName;
    }

    /**
     * Determines whether a persisted Javadoc anchor belongs to this selector's method family.
     *
     * <p>The method name is matched exactly while every overload remains eligible. The selector
     * never infers a parameter signature from learner prose.</p>
     *
     * @param candidateAnchor persisted Javadoc member anchor
     * @return true when the anchor declares the requested method name
     */
    public boolean matchesMethodAnchor(String candidateAnchor) {
        if (candidateAnchor == null || candidateAnchor.isBlank()) {
            return false;
        }
        int parameterClauseStartIndex = candidateAnchor.indexOf('(');
        return parameterClauseStartIndex > 0
                && methodName.equals(candidateAnchor.substring(0, parameterClauseStartIndex));
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

    private Optional<JavaApiMethodSelector> withResolvedJavaPlatformPackage() {
        PlatformMemberLookup platformMemberLookup = new PlatformMemberLookup(packageName, typePageName, methodName);
        return JAVA_PLATFORM_MEMBER_PACKAGE_CACHE
                .computeIfAbsent(platformMemberLookup, JavaApiMethodSelector::resolveUniqueExportedJavaPlatformPackage)
                .map(resolvedPackageName -> resolvedPackageName.equals(packageName)
                        ? this
                        : new JavaApiMethodSelector(
                                resolvedPackageName, typePageName, methodName, invocationSignature));
    }

    private static Optional<String> resolveUniqueExportedJavaPlatformPackage(
            PlatformMemberLookup platformMemberLookup) {
        List<String> matchingPackageNames = new ArrayList<>();
        for (Module platformModule : ModuleLayer.boot().modules()) {
            if (!isJavaPlatformModule(platformModule)) {
                continue;
            }
            for (String exportedPackageName : platformModule.getPackages()) {
                if (!platformModule.isExported(exportedPackageName)
                        || (!platformMemberLookup.packageName().isBlank()
                                && !platformMemberLookup.packageName().equals(exportedPackageName))) {
                    continue;
                }
                String binaryTypeName = platformMemberLookup.typePageName().replace('.', '$');
                Class<?> platformType = Class.forName(platformModule, exportedPackageName + "." + binaryTypeName);
                if (platformType != null
                        && isApiVisible(platformType.getModifiers())
                        && declaresMethod(platformType, platformMemberLookup.methodName())
                        && !matchingPackageNames.contains(exportedPackageName)) {
                    matchingPackageNames.add(exportedPackageName);
                }
            }
        }
        return matchingPackageNames.size() == 1 ? Optional.of(matchingPackageNames.getFirst()) : Optional.empty();
    }

    private static boolean declaresMethod(Class<?> platformType, String expectedMethodName) {
        for (java.lang.reflect.Method declaredMethod : platformType.getDeclaredMethods()) {
            if (isApiVisible(declaredMethod.getModifiers()) && expectedMethodName.equals(declaredMethod.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isApiVisible(int memberModifiers) {
        return java.lang.reflect.Modifier.isPublic(memberModifiers)
                || java.lang.reflect.Modifier.isProtected(memberModifiers);
    }

    private static boolean isJavaPlatformModule(Module candidateModule) {
        String moduleName = candidateModule.getName();
        return moduleName.startsWith("java.") || moduleName.startsWith("jdk.");
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
                int methodReferenceDelimiterIndex = skipWhitespace(query, segmentEndIndex);
                if (hasMethodReferenceDelimiterAt(query, methodReferenceDelimiterIndex)) {
                    int methodStartIndex = skipWhitespace(query, methodReferenceDelimiterIndex + 2);
                    if (isIdentifierStartAt(query, methodStartIndex)) {
                        int methodEndIndex = readIdentifierEnd(query, methodStartIndex);
                        segments.add(query.substring(methodStartIndex, methodEndIndex));
                        return new ParsedQualifiedName(segments, methodEndIndex, true);
                    }
                }
                return new ParsedQualifiedName(segments, segmentEndIndex, false);
            }
            int nextSegmentStartIndex = segmentEndIndex + 1;
            if (!isIdentifierStartAt(query, nextSegmentStartIndex)) {
                return new ParsedQualifiedName(segments, segmentEndIndex, false);
            }
            currentIndex = nextSegmentStartIndex;
        }
        return new ParsedQualifiedName(segments, currentIndex, false);
    }

    private static boolean hasMethodReferenceDelimiterAt(String query, int delimiterStartIndex) {
        return delimiterStartIndex + 1 < query.length()
                && query.charAt(delimiterStartIndex) == ':'
                && query.charAt(delimiterStartIndex + 1) == ':';
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

    private record ParsedQualifiedName(List<String> segments, int endIndex, boolean methodReference) {
        private ParsedQualifiedName {
            segments = List.copyOf(segments);
        }
    }

    private record SelectorOccurrence(
            JavaApiMethodSelector selector, int methodEndIndex, boolean supportsInvocationSignature) {}

    private record PlatformMemberLookup(String packageName, String typePageName, String methodName) {}
}
