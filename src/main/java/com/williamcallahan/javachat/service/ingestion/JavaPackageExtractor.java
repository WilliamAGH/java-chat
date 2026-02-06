package com.williamcallahan.javachat.service.ingestion;

import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import java.util.Objects;

/**
 * Extracts Java package names for ingestion metadata.
 *
 * <p>This is a heuristic tailored for Oracle JavaDoc pages, where the package
 * often appears in the URL path or in a "Package ..." heading in the page text.</p>
 */
public final class JavaPackageExtractor {
    private static final String API_PATH_SEGMENT = "/api/";
    private static final int PACKAGE_EXTRACTION_SNIPPET_LENGTH = 100;

    private JavaPackageExtractor() {}

    /**
     * Attempts to derive a package name from the URL and extracted page text.
     *
     * @param url source URL
     * @param bodyText extracted body text (empty allowed)
     * @return package name when detected, otherwise empty string
     */
    public static String extractPackage(String url, String bodyText) {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(bodyText, "bodyText");

        if (url.contains(API_PATH_SEGMENT)) {
            int idx = url.indexOf(API_PATH_SEGMENT) + API_PATH_SEGMENT.length();
            String tail = url.substring(idx);
            String[] parts = tail.split("/");
            StringBuilder sb = new StringBuilder();
            for (String pathSegment : parts) {
                if (pathSegment.endsWith(".html")) break;
                if (sb.length() > 0) sb.append('.');
                sb.append(pathSegment);
            }
            String pkg = sb.toString();
            if (pkg.contains(".")) return pkg;
        }

        int packageIndex = bodyText.indexOf("Package ");
        if (packageIndex >= 0) {
            int end = Math.min(bodyText.length(), packageIndex + PACKAGE_EXTRACTION_SNIPPET_LENGTH);
            String snippet = bodyText.substring(packageIndex, end);
            for (String token : snippet.split("\\s+")) {
                if (AsciiTextNormalizer.toLowerAscii(token).startsWith("java.")) {
                    return token.replaceAll("[,.;]$", "");
                }
            }
        }

        return "";
    }
}
