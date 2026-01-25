package com.williamcallahan.javachat.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves Javadoc member anchors based on method and constructor signatures.
 */
final class JavadocMemberAnchorResolver {
    private static final String SPRING_DOCS_PREFIX = "https://docs.spring.io/";

    private JavadocMemberAnchorResolver() {}

    static String refineMemberAnchorUrl(String url, String text, String packageName) {
        if (url == null || text == null) {
            return url;
        }
        if (!url.endsWith(".html")) {
            return url;
        }
        // Do not attempt anchor heuristics on Spring docs; their anchors include FQCNs and
        // annotation patterns that differ from JDK javadoc. Avoid risky guesses.
        if (url.contains(SPRING_DOCS_PREFIX)) {
            return url;
        }
        // If URL already has a fragment, respect it
        if (url.contains("#")) {
            return url;
        }

        String classFileName = url.substring(url.lastIndexOf('/') + 1);
        String className = classFileName.substring(0, classFileName.length() - ".html".length());
        String outerSimpleName = className.contains(".") ? className.substring(0, className.indexOf('.')) : className;

        String constructorAnchor = findConstructorAnchor(outerSimpleName, text, packageName, className);
        if (constructorAnchor != null) {
            return url + "#" + constructorAnchor;
        }

        String methodAnchor = findMethodAnchor(text, packageName, className);
        if (methodAnchor != null) {
            return url + "#" + methodAnchor;
        }

        return url;
    }

    private static String findConstructorAnchor(
            String classSimpleName, String text, String packageName, String fullClassName) {
        Pattern constructorPattern = Pattern.compile("\\b" + Pattern.quote(classSimpleName) + "\\s*\\(([^)]*)\\)");
        Matcher constructorMatcher = constructorPattern.matcher(text);
        if (constructorMatcher.find()) {
            String rawParameterList = constructorMatcher.group(1);
            // Ignore annotation-style or class literal params (e.g., SomeType.class)
            if (rawParameterList.contains(".class")) {
                return null;
            }
            String canonicalParams = canonicalizeParams(rawParameterList, packageName, fullClassName);
            if (canonicalParams != null) {
                return "%3Cinit%3E(" + canonicalParams + ")"; // <init>(...)
            }
        }
        return null;
    }

    private static String findMethodAnchor(String text, String packageName, String fullClassName) {
        Pattern methodPattern = Pattern.compile("\\b([a-z][A-Za-z0-9_$]*)\\s*\\(([^)]*)\\)");
        Matcher methodMatcher = methodPattern.matcher(text);
        while (methodMatcher.find()) {
            String methodName = methodMatcher.group(1);
            String rawParameterList = methodMatcher.group(2);
            if (rawParameterList.contains(".class")) {
                continue;
            }
            String canonicalParams = canonicalizeParams(rawParameterList, packageName, fullClassName);
            if (canonicalParams != null) {
                return methodName + "(" + canonicalParams + ")";
            }
        }
        return null;
    }

    private static String canonicalizeParams(String rawParameterList, String packageName, String fullClassName) {
        String trimmedParams = rawParameterList.trim();
        if (trimmedParams.isEmpty()) {
            return ""; // no-arg
        }

        String[] parameterTokens = splitParams(trimmedParams);
        StringBuilder canonicalBuilder = new StringBuilder();
        for (String token : parameterTokens) {
            String canonicalType = JavadocTypeCanonicalizer.canonicalizeType(token.trim(), packageName, fullClassName);
            if (canonicalType == null) {
                return null; // abort if an unknown type can't be resolved confidently
            }
            if (canonicalBuilder.length() > 0) {
                canonicalBuilder.append(',');
            }
            canonicalBuilder.append(canonicalType);
        }
        return canonicalBuilder.toString();
    }

    private static String[] splitParams(String rawParams) {
        // Split on commas not inside generics '<>'
        int genericDepth = 0;
        StringBuilder currentToken = new StringBuilder();
        List<String> tokens = new ArrayList<>();
        for (int cursor = 0; cursor < rawParams.length(); cursor++) {
            char currentChar = rawParams.charAt(cursor);
            if (currentChar == '<') {
                genericDepth++;
            } else if (currentChar == '>' && genericDepth > 0) {
                genericDepth--;
            }
            if (currentChar == ',' && genericDepth == 0) {
                tokens.add(currentToken.toString());
                currentToken.setLength(0);
                continue;
            }
            currentToken.append(currentChar);
        }
        if (currentToken.length() > 0) {
            tokens.add(currentToken.toString());
        }
        return tokens.toArray(new String[0]);
    }
}
