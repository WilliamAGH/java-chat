package com.williamcallahan.javachat.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves nested type Javadoc URLs based on detected references in text.
 */
final class JavadocNestedTypeResolver {
    private JavadocNestedTypeResolver() {}

    /**
     * Resolves a nested type URL when the text references a nested class.
     *
     * @param url original class URL
     * @param text extracted text for the page
     * @return nested type URL when detected, otherwise the original URL
     */
    static String refineNestedTypeUrl(String url, String text) {
        if (url == null || text == null) {
            return url;
        }
        if (!url.endsWith(".html")) {
            return url;
        }

        String fileName = url.substring(url.lastIndexOf('/') + 1);
        String basePath = url.substring(0, url.length() - fileName.length());
        String outerTypeName =
                fileName.endsWith(".html") ? fileName.substring(0, fileName.length() - ".html".length()) : fileName;

        // Already a nested type page
        if (outerTypeName.contains(".")) {
            return url;
        }

        // Look for Outer.Inner or deeper in the text
        // Pattern: Outer.(Inner(.Deep)*) where Outer is the current page type name
        Pattern nestedTypePattern = Pattern.compile(
                "\\b" + Pattern.quote(outerTypeName) + "\\.([A-Z][A-Za-z0-9_]*(?:\\.[A-Z][A-Za-z0-9_]*)*)\\b");
        Matcher nestedTypeMatcher = nestedTypePattern.matcher(text);
        if (nestedTypeMatcher.find()) {
            String nestedSuffix = nestedTypeMatcher.group(1); // e.g., "Operator" or "Inner.Deep"
            String nestedFile = outerTypeName + "." + nestedSuffix + ".html";
            return basePath + nestedFile;
        }

        return url;
    }
}
