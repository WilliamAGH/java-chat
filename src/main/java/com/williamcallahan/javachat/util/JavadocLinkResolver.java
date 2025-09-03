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
            String canon = canonicalizeType(t.trim(), packageName, fullClassName);
            if (canon == null) return null; // abort if an unknown type can't be resolved confidently
            if (out.length() > 0) out.append(',');
            out.append(canon);
        }
        return out.toString();
    }

    private static String[] splitParams(String s) {
        // Split on commas not inside generics '<>'
        int depth = 0; boolean inArray = false;
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

    private static String canonicalizeType(String raw, String packageName, String fullClassName) {
        String t = raw.trim();
        if (t.isEmpty()) return null;

        // Remove parameter names if present (assume last whitespace splits type and name)
        // But be careful with generic spaces; so only split on last space not inside generics
        t = stripParamName(t);

        // Strip generics
        t = stripGenerics(t);

        // Handle varargs and arrays
        boolean varargs = t.endsWith("...");
        if (varargs) t = t.substring(0, t.length() - 3).trim();
        int arrayDims = 0;
        while (t.endsWith("[]")) { arrayDims++; t = t.substring(0, t.length() - 2).trim(); }

        // Primitives and void
        if (isPrimitiveOrVoid(t)) {
            String base = t;
            String suffix = varargs ? "..." : repeatArray(arrayDims);
            return base + suffix;
        }

        // java.lang simple types
        String javaLang = mapJavaLang(t);
        if (javaLang != null) {
            String suffix = varargs ? "..." : repeatArray(arrayDims);
            return javaLang + suffix;
        }

        // If already qualified with a package
        if (t.contains(".")) {
            // Relative to current package (e.g., Locale.Category)
            if (Character.isUpperCase(t.charAt(0))) {
                String pkg = (packageName == null || packageName.isBlank()) ? null : packageName;
                if (pkg != null) {
                    String fqn = pkg + "." + t;
                    String suffix = varargs ? "..." : repeatArray(arrayDims);
                    return fqn + suffix;
                }
            }
            // Looks fully qualified already
            String suffix = varargs ? "..." : repeatArray(arrayDims);
            return t + suffix;
        }

        // Possibly nested type of current class (e.g., Builder on Outer page)
        if (Character.isUpperCase(t.charAt(0))) {
            String pkg = (packageName == null || packageName.isBlank()) ? null : packageName;
            if (pkg != null) {
                // fullClassName may be Outer or Outer.Inner
                String enclosing = fullClassName;
                String fqn = pkg + "." + enclosing + "." + t;
                String suffix = varargs ? "..." : repeatArray(arrayDims);
                return fqn + suffix;
            }
        }

        // Unknown non-java.lang simple type; avoid producing a bad anchor
        return null;
    }

    private static String stripGenerics(String x) {
        int depth = 0; StringBuilder sb = new StringBuilder();
        for (int i = 0; i < x.length(); i++) {
            char c = x.charAt(i);
            if (c == '<') { depth++; continue; }
            if (c == '>') { if (depth > 0) depth--; continue; }
            if (depth == 0) sb.append(c);
        }
        return sb.toString().trim();
    }

    private static String stripParamName(String x) {
        int depth = 0; int lastSpace = -1;
        for (int i = 0; i < x.length(); i++) {
            char c = x.charAt(i);
            if (c == '<') depth++;
            else if (c == '>' && depth > 0) depth--;
            else if (c == ' ' && depth == 0) lastSpace = i;
        }
        if (lastSpace >= 0) return x.substring(0, lastSpace).trim();
        return x.trim();
    }

    private static boolean isPrimitiveOrVoid(String t) {
        return t.equals("byte") || t.equals("short") || t.equals("int") || t.equals("long") ||
               t.equals("char") || t.equals("float") || t.equals("double") || t.equals("boolean") ||
               t.equals("void");
    }

    private static String mapJavaLang(String t) {
        return switch (t) {
            case "String" -> "java.lang.String";
            case "Integer" -> "java.lang.Integer";
            case "Long" -> "java.lang.Long";
            case "Short" -> "java.lang.Short";
            case "Byte" -> "java.lang.Byte";
            case "Character" -> "java.lang.Character";
            case "Boolean" -> "java.lang.Boolean";
            case "Double" -> "java.lang.Double";
            case "Float" -> "java.lang.Float";
            case "Void" -> "java.lang.Void";
            case "Object" -> "java.lang.Object";
            case "Class" -> "java.lang.Class";
            default -> null;
        };
    }

    private static String repeatArray(int dims) {
        if (dims <= 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dims; i++) sb.append("[]");
        return sb.toString();
    }
}
