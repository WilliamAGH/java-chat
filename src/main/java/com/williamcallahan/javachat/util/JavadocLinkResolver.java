package com.williamcallahan.javachat.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilities to refine Javadoc links, e.g., point to nested type pages
 * like Outer.Inner.html when the chunk text clearly references a nested type.
 */
public final class JavadocLinkResolver {
    private JavadocLinkResolver() {}

    /**
     * If the provided URL looks like a Javadoc class page (Outer.html) and the
     * text references a nested type like Outer.Inner, return a URL pointing to
     * the nested type page (Outer.Inner.html). Otherwise, return the original URL.
     */
    public static String refineNestedTypeUrl(String url, String text) {
        if (url == null || text == null) return url;
        if (!url.endsWith(".html")) return url;

        String fileName = url.substring(url.lastIndexOf('/') + 1);
        String basePath = url.substring(0, url.length() - fileName.length());
        String outer = fileName.endsWith(".html")
                ? fileName.substring(0, fileName.length() - ".html".length())
                : fileName;

        // Already a nested type page
        if (outer.contains(".")) return url;

        // Look for Outer.Inner or deeper in the text
        // Pattern: Outer.(Inner(.Deep)*) where Outer is the current page type name
        Pattern p = Pattern.compile("\\b" + Pattern.quote(outer) + "\\.([A-Z][A-Za-z0-9_]*(?:\\.[A-Z][A-Za-z0-9_]*)*)\\b");
        Matcher m = p.matcher(text);
        if (m.find()) {
            String nestedSuffix = m.group(1); // e.g., "Operator" or "Inner.Deep"
            String nestedFile = outer + "." + nestedSuffix + ".html";
            return basePath + nestedFile;
        }

        return url;
    }

    /**
     * Attempt to append a method/constructor anchor to a Javadoc class page URL
     * using signatures detected in the provided text. This is heuristic but designed
     * to be reliable for common cases (java.lang simple types, same-package types,
     * and nested types of the current class).
     *
     * @param url         Javadoc page URL ending with .html
     * @param text        Extracted chunk text from the same page
     * @param packageName Package name of the type (from metadata), can be empty
     * @return URL with a fragment to the member if a confident match is found; original URL otherwise
     */
    public static String refineMemberAnchorUrl(String url, String text, String packageName) {
        if (url == null || text == null) return url;
        if (!url.endsWith(".html")) return url;
        // Do not attempt anchor heuristics on Spring docs; their anchors include FQCNs and
        // annotation patterns that differ from JDK javadoc. Avoid risky guesses.
        if (url.contains("https://docs.spring.io/")) return url;
        // If URL already has a fragment, respect it
        if (url.contains("#")) return url;

        String classFile = url.substring(url.lastIndexOf('/') + 1);
        String className = classFile.substring(0, classFile.length() - ".html".length()); // e.g., Outer or Outer.Inner
        String outerSimple = className.contains(".") ? className.substring(0, className.indexOf('.')) : className;

        // Find a method-like token: name(params)
        // Prefer constructor-like (ClassName(...)) if present
        String constructorAnchor = findConstructorAnchor(outerSimple, text, packageName, className);
        if (constructorAnchor != null) {
            return url + "#" + constructorAnchor;
        }

        String methodAnchor = findMethodAnchor(text, packageName, className);
        if (methodAnchor != null) {
            return url + "#" + methodAnchor;
        }

        return url;
    }

    private static String findConstructorAnchor(String classSimple, String text, String packageName, String fullClassName) {
        // Pattern matching 'ClassName(...)'
        Pattern p = Pattern.compile("\\b" + Pattern.quote(classSimple) + "\\s*\\(([^)]*)\\)");
        Matcher m = p.matcher(text);
        if (m.find()) {
            String paramsRaw = m.group(1);
            // Ignore annotation-style or class literal params (e.g., SomeType.class)
            if (paramsRaw.contains(".class")) return null;
            String paramsCanon = canonicalizeParams(paramsRaw, packageName, fullClassName);
            if (paramsCanon != null) {
                return "%3Cinit%3E(" + paramsCanon + ")"; // <init>(...)
            }
        }
        return null;
    }

    private static String findMethodAnchor(String text, String packageName, String fullClassName) {
        // methodName(...) where methodName starts with lowercase letter
        Pattern p = Pattern.compile("\\b([a-z][A-Za-z0-9_$]*)\\s*\\(([^)]*)\\)");
        Matcher m = p.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            String paramsRaw = m.group(2);
            if (paramsRaw.contains(".class")) continue; // avoid class literal cases
            String paramsCanon = canonicalizeParams(paramsRaw, packageName, fullClassName);
            if (paramsCanon != null) {
                return name + "(" + paramsCanon + ")";
            }
        }
        return null;
    }

    private static String canonicalizeParams(String paramsRaw, String packageName, String fullClassName) {
        String trimmed = paramsRaw.trim();
        if (trimmed.isEmpty()) return ""; // no-arg

        String[] tokens = splitParams(trimmed);
        StringBuilder out = new StringBuilder();
        for (String t : tokens) {
            String canon = JavadocTypeCanonicalizer.canonicalizeType(t.trim(), packageName, fullClassName);
            if (canon == null) return null; // abort if an unknown type can't be resolved confidently
            if (out.length() > 0) out.append(',');
            out.append(canon);
        }
        return out.toString();
    }

    private static String[] splitParams(String s) {
        // Split on commas not inside generics '<>'
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        java.util.List<String> list = new java.util.ArrayList<>();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') depth++;
            else if (c == '>' && depth > 0) depth--;
            if (c == ',' && depth == 0) {
                list.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) list.add(cur.toString());
        return list.toArray(new String[0]);
    }
}

