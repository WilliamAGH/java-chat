package com.williamcallahan.javachat.service.ingestion;

import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.DocsSourceRegistry.JavaApiDocumentationSource;
import com.williamcallahan.javachat.domain.javaapi.JavaPackageName;
import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.Optional;

/**
 * Extracts Java package names for ingestion metadata.
 *
 * <p>Configured Java API URLs yield package paths directly; other documents fall back to their
 * extracted "Package ..." heading.</p>
 */
public final class JavaPackageExtractor {
    private static final int PACKAGE_EXTRACTION_SNIPPET_LENGTH = 100;
    private static final int MINIMUM_JAVA_API_PATH_SEGMENT_COUNT = 2;

    private JavaPackageExtractor() {}

    /**
     * Identifies URLs whose paths point to Java API documentation.
     *
     * <p>The Java API source configuration owns matching source bases. Only a URL's scheme, authority, and
     * path participate in classification, so query and fragment text cannot select structured extraction.</p>
     *
     * @param url source URL
     * @return {@code true} when the URL belongs to a canonical Java API source
     */
    public static boolean isJavaApiUrl(String url) {
        Objects.requireNonNull(url, "url");
        return findJavaApiSourceUrl(url).isPresent();
    }

    /**
     * Derives the Java package encoded by a configured Java API source URL.
     *
     * <p>URL consumers use this projection instead of persisted package metadata so package
     * identity follows the canonical source path across ingestion generations.</p>
     *
     * @param url source URL
     * @return package encoded by the canonical Java API path, or an empty string when the URL is
     *     not a canonical Java API source or its path does not identify a package
     */
    public static String extractJavaApiPackage(String url) {
        Objects.requireNonNull(url, "url");
        return findJavaApiSourceUrl(url)
                .map(JavaPackageExtractor::extractPackageFromJavaApiPath)
                .flatMap(JavaPackageName::from)
                .map(JavaPackageName::qualifiedName)
                .orElse("");
    }

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

        String pathDerivedPackageName = extractJavaApiPackage(url);
        if (!pathDerivedPackageName.isBlank()) {
            return pathDerivedPackageName;
        }

        int packageIndex = bodyText.indexOf("Package ");
        if (packageIndex >= 0) {
            int end = Math.min(bodyText.length(), packageIndex + PACKAGE_EXTRACTION_SNIPPET_LENGTH);
            String snippet = bodyText.substring(packageIndex, end);
            for (String token : snippet.split("\\s+")) {
                if (AsciiTextNormalizer.toLowerAscii(token).startsWith("java.")) {
                    String candidatePackageName = token.replaceAll("[,.;]$", "");
                    return JavaPackageName.from(candidatePackageName)
                            .map(JavaPackageName::qualifiedName)
                            .orElse("");
                }
            }
        }

        return "";
    }

    private static Optional<JavaApiSourceUrl> findJavaApiSourceUrl(String url) {
        URI documentUri;
        try {
            documentUri = new URI(url);
        } catch (URISyntaxException invalidUrlException) {
            return Optional.empty();
        }

        return DocsSourceRegistry.javaApiDocumentationSources().stream()
                .filter(javaApiSource -> belongsToJavaApiSource(documentUri, javaApiSource))
                .findFirst()
                .map(javaApiSource -> new JavaApiSourceUrl(documentUri, javaApiSource));
    }

    private static boolean belongsToJavaApiSource(URI documentUri, JavaApiDocumentationSource javaApiSource) {
        URI javaApiBaseUri = URI.create(javaApiSource.remoteBaseUrl());
        String documentRawPath = documentUri.getRawPath();
        return javaApiBaseUri.getScheme().equalsIgnoreCase(documentUri.getScheme())
                && javaApiBaseUri.getHost().equalsIgnoreCase(documentUri.getHost())
                && javaApiBaseUri.getPort() == documentUri.getPort()
                && documentRawPath != null
                && documentRawPath.startsWith(javaApiBaseUri.getRawPath());
    }

    private static String extractPackageFromJavaApiPath(JavaApiSourceUrl javaApiSourceUrl) {
        URI javaApiBaseUri = URI.create(javaApiSourceUrl.javaApiSource().remoteBaseUrl());
        String apiRelativePath = javaApiSourceUrl
                .documentUri()
                .getRawPath()
                .substring(javaApiBaseUri.getRawPath().length());
        String[] apiPathSegments = apiRelativePath.split("/");
        if (apiPathSegments.length < MINIMUM_JAVA_API_PATH_SEGMENT_COUNT) {
            return "";
        }

        StringBuilder packageBuilder = new StringBuilder();
        for (int pathSegmentIndex = 1; pathSegmentIndex < apiPathSegments.length; pathSegmentIndex++) {
            String apiPathSegment = apiPathSegments[pathSegmentIndex];
            if (apiPathSegment.endsWith(".html")) {
                break;
            }
            if (apiPathSegment.isBlank()) {
                continue;
            }
            if (packageBuilder.length() > 0) {
                packageBuilder.append('.');
            }
            packageBuilder.append(apiPathSegment);
        }
        String packageName = packageBuilder.toString();
        return packageName.contains(".") ? packageName : "";
    }

    private record JavaApiSourceUrl(URI documentUri, JavaApiDocumentationSource javaApiSource) {}
}
