package com.williamcallahan.javachat.util;

final class JavadocTypeCanonicalizer {

    private JavadocTypeCanonicalizer() {}

    static String canonicalizeType(String raw, String packageName, String fullClassName) {
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
