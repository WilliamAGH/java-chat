package com.williamcallahan.javachat.util;

import java.util.Optional;

/**
 * Canonicalizes Javadoc parameter type tokens into fully qualified names.
 */
final class JavadocTypeCanonicalizer {

    private JavadocTypeCanonicalizer() {}

    /**
     * Canonicalizes a raw type token from Javadoc text into a stable signature form.
     *
     * @param rawType raw type token extracted from Javadoc
     * @param packageName package of the enclosing class
     * @param fullClassName fully qualified class name for nested type resolution
     * @return canonicalized type signature when the type can be resolved
     */
    static Optional<String> canonicalizeType(String rawType, String packageName, String fullClassName) {
        String trimmedType = rawType.trim();
        if (trimmedType.isEmpty()) {
            return Optional.empty();
        }

        // Remove parameter names if present (assume last whitespace splits type and name)
        trimmedType = stripParamName(trimmedType);

        // Strip generics
        trimmedType = stripGenerics(trimmedType);

        // Handle varargs and arrays
        boolean isVarargs = trimmedType.endsWith("...");
        if (isVarargs) {
            trimmedType = trimmedType.substring(0, trimmedType.length() - 3).trim();
        }
        int arrayDimensions = countArrayDimensions(trimmedType);
        trimmedType = trimArraySuffix(trimmedType, arrayDimensions);

        // Primitives and void
        if (isPrimitiveOrVoid(trimmedType)) {
            return Optional.of(appendSuffix(trimmedType, isVarargs, arrayDimensions));
        }

        // java.lang simple types
        Optional<String> javaLangType = mapJavaLang(trimmedType);
        if (javaLangType.isPresent()) {
            return javaLangType.map(
                    resolvedJavaLangType -> appendSuffix(resolvedJavaLangType, isVarargs, arrayDimensions));
        }

        // If already qualified with a package
        if (trimmedType.contains(".")) {
            Optional<String> qualifiedType =
                    resolveRelativeQualifiedType(trimmedType, packageName, arrayDimensions, isVarargs);
            if (qualifiedType.isPresent()) {
                return qualifiedType;
            }
            return Optional.of(appendSuffix(trimmedType, isVarargs, arrayDimensions));
        }

        // Possibly nested type of current class (e.g., Builder on Outer page)
        if (Character.isUpperCase(trimmedType.charAt(0))) {
            String packagePrefix = (packageName == null || packageName.isBlank()) ? null : packageName;
            if (packagePrefix != null) {
                String enclosing = fullClassName;
                String fullyQualified = packagePrefix + "." + enclosing + "." + trimmedType;
                return Optional.of(appendSuffix(fullyQualified, isVarargs, arrayDimensions));
            }
        }

        // Unknown non-java.lang simple type; avoid producing a bad anchor
        return Optional.empty();
    }

    private static Optional<String> resolveRelativeQualifiedType(
            String trimmedType, String packageName, int arrayDimensions, boolean isVarargs) {
        if (!Character.isUpperCase(trimmedType.charAt(0))) {
            return Optional.empty();
        }
        String packagePrefix = (packageName == null || packageName.isBlank()) ? null : packageName;
        if (packagePrefix == null) {
            return Optional.empty();
        }
        String fullyQualified = packagePrefix + "." + trimmedType;
        return Optional.of(appendSuffix(fullyQualified, isVarargs, arrayDimensions));
    }

    private static int countArrayDimensions(String typeText) {
        int arrayDimensions = 0;
        while (typeText.endsWith("[]")) {
            arrayDimensions++;
            typeText = typeText.substring(0, typeText.length() - 2).trim();
        }
        return arrayDimensions;
    }

    private static String trimArraySuffix(String typeText, int arrayDimensions) {
        String trimmed = typeText;
        for (int dimensionIndex = 0; dimensionIndex < arrayDimensions; dimensionIndex++) {
            trimmed = trimmed.substring(0, trimmed.length() - 2).trim();
        }
        return trimmed;
    }

    private static String appendSuffix(String baseType, boolean isVarargs, int arrayDimensions) {
        String suffix = isVarargs ? "..." : repeatArray(arrayDimensions);
        return baseType + suffix;
    }

    private static String stripGenerics(String typeText) {
        int genericDepth = 0;
        StringBuilder builder = new StringBuilder();
        for (int charIndex = 0; charIndex < typeText.length(); charIndex++) {
            char currentChar = typeText.charAt(charIndex);
            if (currentChar == '<') {
                genericDepth++;
                continue;
            }
            if (currentChar == '>') {
                if (genericDepth > 0) {
                    genericDepth--;
                }
                continue;
            }
            if (genericDepth == 0) {
                builder.append(currentChar);
            }
        }
        return builder.toString().trim();
    }

    private static String stripParamName(String typeText) {
        int genericDepth = 0;
        int lastSpaceIndex = -1;
        for (int charIndex = 0; charIndex < typeText.length(); charIndex++) {
            char currentChar = typeText.charAt(charIndex);
            if (currentChar == '<') {
                genericDepth++;
            } else if (currentChar == '>' && genericDepth > 0) {
                genericDepth--;
            } else if (currentChar == ' ' && genericDepth == 0) {
                lastSpaceIndex = charIndex;
            }
        }
        if (lastSpaceIndex >= 0) {
            return typeText.substring(0, lastSpaceIndex).trim();
        }
        return typeText.trim();
    }

    private static boolean isPrimitiveOrVoid(String typeText) {
        return typeText.equals("byte")
                || typeText.equals("short")
                || typeText.equals("int")
                || typeText.equals("long")
                || typeText.equals("char")
                || typeText.equals("float")
                || typeText.equals("double")
                || typeText.equals("boolean")
                || typeText.equals("void");
    }

    private static Optional<String> mapJavaLang(String typeText) {
        return Optional.ofNullable(
                switch (typeText) {
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
                });
    }

    private static String repeatArray(int arrayDimensions) {
        if (arrayDimensions <= 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int dimensionIndex = 0; dimensionIndex < arrayDimensions; dimensionIndex++) {
            builder.append("[]");
        }
        return builder.toString();
    }
}
