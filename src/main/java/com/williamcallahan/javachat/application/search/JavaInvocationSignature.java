package com.williamcallahan.javachat.application.search;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Captures a source-anchor-compatible Java parameter signature written directly in a learner query.
 *
 * <p>The parser intentionally accepts only unambiguous Java type syntax. It leaves value expressions,
 * generics, wildcards, nested calls, and malformed parentheses unavailable so citation retrieval keeps
 * its broad relevance behavior instead of guessing an overload.</p>
 */
record JavaInvocationSignature(boolean isExact, String normalizedParameterClause) {

    private static final String EMPTY_PARAMETER_CLAUSE = "()";
    private static final String VARARGS_SUFFIX = "...";
    private static final Set<String> PRIMITIVE_TYPE_NAMES =
            Set.of("boolean", "byte", "short", "int", "long", "char", "float", "double");

    JavaInvocationSignature {
        Objects.requireNonNull(normalizedParameterClause, "normalizedParameterClause");
        if (isExact
                && (normalizedParameterClause.length() < EMPTY_PARAMETER_CLAUSE.length()
                        || normalizedParameterClause.charAt(0) != '('
                        || normalizedParameterClause.charAt(normalizedParameterClause.length() - 1) != ')')) {
            throw new IllegalArgumentException("Exact parameter clauses must be parenthesized");
        }
        if (!isExact && !normalizedParameterClause.isEmpty()) {
            throw new IllegalArgumentException("Unavailable signatures cannot retain parameter syntax");
        }
    }

    static JavaInvocationSignature unavailable() {
        return new JavaInvocationSignature(false, "");
    }

    static JavaInvocationSignature afterMethodName(String queryText, int methodEndIndex) {
        Objects.requireNonNull(queryText, "queryText");
        int openingParenthesisIndex = skipWhitespace(queryText, methodEndIndex);
        if (openingParenthesisIndex >= queryText.length() || queryText.charAt(openingParenthesisIndex) != '(') {
            return unavailable();
        }
        return parseParameterClause(queryText, openingParenthesisIndex);
    }

    Optional<String> anchorFor(String methodName) {
        String requiredMethodName = Objects.requireNonNull(methodName, "methodName");
        return isExact ? Optional.of(requiredMethodName + normalizedParameterClause) : Optional.empty();
    }

    private static JavaInvocationSignature parseParameterClause(String queryText, int openingParenthesisIndex) {
        int currentIndex = skipWhitespace(queryText, openingParenthesisIndex + 1);
        if (currentIndex < queryText.length() && queryText.charAt(currentIndex) == ')') {
            return new JavaInvocationSignature(true, EMPTY_PARAMETER_CLAUSE);
        }

        StringBuilder normalizedParameterClause = new StringBuilder("(");
        while (currentIndex < queryText.length()) {
            ParsedParameterType parsedParameterType = parseParameterType(queryText, currentIndex);
            if (parsedParameterType == null) {
                return unavailable();
            }
            normalizedParameterClause.append(parsedParameterType.normalizedType());
            currentIndex = skipWhitespace(queryText, parsedParameterType.endIndex());
            if (currentIndex >= queryText.length()) {
                return unavailable();
            }
            char followingCharacter = queryText.charAt(currentIndex);
            if (followingCharacter == ')') {
                normalizedParameterClause.append(')');
                return new JavaInvocationSignature(true, normalizedParameterClause.toString());
            }
            if (followingCharacter != ',') {
                return unavailable();
            }
            normalizedParameterClause.append(',');
            currentIndex = skipWhitespace(queryText, currentIndex + 1);
        }
        return unavailable();
    }

    private static ParsedParameterType parseParameterType(String queryText, int startIndex) {
        int currentIndex = skipWhitespace(queryText, startIndex);
        StringBuilder normalizedType = new StringBuilder();
        int typeSegmentCount = 0;
        String firstTypeSegment = "";
        String terminalTypeSegment = "";

        while (true) {
            int typeSegmentEndIndex = readIdentifierEnd(queryText, currentIndex);
            if (typeSegmentEndIndex < 0) {
                return null;
            }
            String typeSegment = queryText.substring(currentIndex, typeSegmentEndIndex);
            if (typeSegmentCount == 0) {
                firstTypeSegment = typeSegment;
            }
            if (typeSegmentCount > 0) {
                normalizedType.append('.');
            }
            normalizedType.append(typeSegment);
            terminalTypeSegment = typeSegment;
            typeSegmentCount++;
            currentIndex = skipWhitespace(queryText, typeSegmentEndIndex);
            if (hasVarargsSuffixAt(queryText, currentIndex)) {
                break;
            }
            if (currentIndex >= queryText.length() || queryText.charAt(currentIndex) != '.') {
                break;
            }
            currentIndex = skipWhitespace(queryText, currentIndex + 1);
        }

        if (!isSupportedTypeName(firstTypeSegment, terminalTypeSegment, typeSegmentCount)) {
            return null;
        }

        boolean hasArraySuffix = false;
        while (true) {
            currentIndex = skipWhitespace(queryText, currentIndex);
            if (hasVarargsSuffixAt(queryText, currentIndex)) {
                if (hasArraySuffix) {
                    return null;
                }
                normalizedType.append(VARARGS_SUFFIX);
                currentIndex += VARARGS_SUFFIX.length();
                return new ParsedParameterType(normalizedType.toString(), currentIndex);
            }
            if (currentIndex >= queryText.length() || queryText.charAt(currentIndex) != '[') {
                return new ParsedParameterType(normalizedType.toString(), currentIndex);
            }
            int closingArrayBracketIndex = skipWhitespace(queryText, currentIndex + 1);
            if (closingArrayBracketIndex >= queryText.length() || queryText.charAt(closingArrayBracketIndex) != ']') {
                return null;
            }
            normalizedType.append("[]");
            currentIndex = closingArrayBracketIndex + 1;
            hasArraySuffix = true;
        }
    }

    private static boolean isSupportedTypeName(
            String firstTypeSegment, String terminalTypeSegment, int typeSegmentCount) {
        if (typeSegmentCount == 1) {
            return PRIMITIVE_TYPE_NAMES.contains(terminalTypeSegment)
                    || (terminalTypeSegment.length() == 1 && Character.isUpperCase(terminalTypeSegment.charAt(0)));
        }
        return Character.isLowerCase(firstTypeSegment.charAt(0))
                && Character.isUpperCase(terminalTypeSegment.charAt(0))
                && !isAllUppercaseIdentifier(terminalTypeSegment);
    }

    private static boolean isAllUppercaseIdentifier(String identifier) {
        if (identifier.length() <= 1) {
            return false;
        }
        boolean containsLetter = false;
        for (int currentIndex = 0; currentIndex < identifier.length(); currentIndex++) {
            char currentCharacter = identifier.charAt(currentIndex);
            if (Character.isLetter(currentCharacter)) {
                containsLetter = true;
                if (!Character.isUpperCase(currentCharacter)) {
                    return false;
                }
            }
        }
        return containsLetter;
    }

    private static boolean hasVarargsSuffixAt(String queryText, int currentIndex) {
        return currentIndex + VARARGS_SUFFIX.length() <= queryText.length()
                && queryText.startsWith(VARARGS_SUFFIX, currentIndex);
    }

    private static int readIdentifierEnd(String queryText, int startIndex) {
        if (startIndex >= queryText.length() || !Character.isJavaIdentifierStart(queryText.charAt(startIndex))) {
            return -1;
        }
        int currentIndex = startIndex + 1;
        while (currentIndex < queryText.length() && Character.isJavaIdentifierPart(queryText.charAt(currentIndex))) {
            currentIndex++;
        }
        return currentIndex;
    }

    private static int skipWhitespace(String queryText, int startIndex) {
        int currentIndex = startIndex;
        while (currentIndex < queryText.length() && Character.isWhitespace(queryText.charAt(currentIndex))) {
            currentIndex++;
        }
        return currentIndex;
    }

    private record ParsedParameterType(String normalizedType, int endIndex) {}
}
