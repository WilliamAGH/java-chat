package com.williamcallahan.javachat.config;

/**
 * Utility class for normalizing and cleaning Spring documentation URLs and paths.
 * Extracted from DocsSourceRegistry to reduce cyclomatic complexity.
 */
final class SpringDocsUtils {

    private SpringDocsUtils() {}

    /**
     * Normalize Spring documentation URLs to their correct canonical form.
     * Handles various malformed scraped URL patterns.
     */
    static String normalizeUrl(String url) {
        // Parse the URL path after docs.spring.io/
        String prefix = "https://docs.spring.io/";
        if (!url.startsWith(prefix)) {
            return url;
        }

        String path = url.substring(prefix.length());
        String[] parts = path.split("/");

        if (parts.length < 2) {
            return url;
        }

        String project = parts[0]; // e.g., "spring-framework" or "spring-boot"

        // Handle Spring Framework URLs
        if (project.equals("spring-framework")) {
            return normalizeSpringFrameworkUrl(prefix, parts);
        }

        // Handle Spring Boot URLs
        if (project.equals("spring-boot")) {
            return normalizeSpringBootUrl(prefix, parts);
        }

        return url;
    }

    private static String normalizeSpringFrameworkUrl(String prefix, String[] parts) {
        // Case 1: /spring-framework/docs/current/reference/6.1-SNAPSHOT/...
        // Should be: /spring-framework/reference/current/...
        if (parts.length > 4 && parts[1].equals("docs") && parts[2].equals("current") && parts[3].equals("reference")) {
            // Check if parts[4] is a version string, if so skip it
            int startIndex = 4;
            if (isVersionString(parts[4])) {
                startIndex = 5; // Skip the version
            }
            return buildPath(prefix, "spring-framework/reference/current", parts, startIndex);
        }

        // Case 2: /spring-framework/reference/7.0-SNAPSHOT/...
        // Should be: /spring-framework/reference/current/...
        if (parts.length > 2 && parts[1].equals("reference") && isVersionString(parts[2])) {
            return buildPath(prefix, "spring-framework/reference/current", parts, 3);
        }

        // Case 3a: /spring-framework/docs/current/api/current/javadoc-api/...
        // Should be: /spring-framework/docs/current/javadoc-api/...
        if (parts.length > 5 && parts[1].equals("docs") && parts[2].equals("current")
                && parts[3].equals("api") && parts[4].equals("current") && parts[5].equals("javadoc-api")) {
            return buildPath(prefix, "spring-framework/docs/current/javadoc-api", parts, 6);
        }
        // Case 3b: /spring-framework/docs/current/javadoc-api/java/... -> remove spurious 'java/'
        if (parts.length > 4 && parts[1].equals("docs") && parts[2].equals("current")
                && parts[3].equals("javadoc-api") && parts[4].equals("java")) {
            return buildPath(prefix, "spring-framework/docs/current/javadoc-api", parts, 5);
        }
        
        // If no match, reconstruct original
        return reconstructOriginal(prefix, parts);
    }

    private static String normalizeSpringBootUrl(String prefix, String[] parts) {
        // Case 1: /spring-boot/docs/current/reference/VERSION/...
        // Should be: /spring-boot/reference/current/...
        if (parts.length > 4 && parts[1].equals("docs") && parts[2].equals("current") && parts[3].equals("reference")) {
            // Check if parts[4] is a version string, if so skip it
            int startIndex = 4;
            if (isVersionString(parts[4])) {
                startIndex = 5; // Skip the version
            }
            return buildPath(prefix, "spring-boot/reference/current", parts, startIndex);
        }

        // Case 2a: /spring-boot/reference/VERSION/...
        // Should be: /spring-boot/reference/current/...
        if (parts.length > 2 && parts[1].equals("reference") && isVersionString(parts[2])) {
            return buildPath(prefix, "spring-boot/reference/current", parts, 3);
        }
        // Case 2b: /spring-boot/docs/current/api/java/... -> remove spurious 'java/'
        if (parts.length > 4 && parts[1].equals("docs") && parts[2].equals("current")
                && parts[3].equals("api") && parts[4].equals("java")) {
            return buildPath(prefix, "spring-boot/docs/current/api", parts, 5);
        }

        return reconstructOriginal(prefix, parts);
    }
    
    private static String buildPath(String prefix, String newBase, String[] parts, int startIndex) {
        StringBuilder newPath = new StringBuilder(prefix);
        newPath.append(newBase);
        for (int i = startIndex; i < parts.length; i++) {
            newPath.append("/").append(parts[i]);
        }
        return newPath.toString();
    }
    
    private static String reconstructOriginal(String prefix, String[] parts) {
        StringBuilder sb = new StringBuilder(prefix);
        sb.append(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            sb.append("/").append(parts[i]);
        }
        return sb.toString();
    }

    /**
     * Check if a string looks like a version (e.g., "6.1-SNAPSHOT", "7.0", "3.2.1")
     */
    static boolean isVersionString(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        // Check for patterns like X.Y, X.Y.Z, X.Y-SNAPSHOT
        return s.contains(".") && (Character.isDigit(s.charAt(0)) || s.charAt(0) == 'v');
    }
    
    static String cleanRelativePath(String rel, boolean isFramework, boolean isBoot) {
        String result = rel;
        
        if (isFramework) {
            // Handle various scraped path structures
            if (result.startsWith("docs/current/api/current/javadoc-api/")) {
                // Fix double "current" in path: docs/current/api/current/javadoc-api/ -> javadoc-api/
                result = result.substring("docs/current/api/current/".length());
            } else if (result.startsWith("api/current/javadoc-api/")) {
                // Remove api/current/ prefix: api/current/javadoc-api/ -> javadoc-api/
                result = result.substring("api/current/".length());
            } else if (result.startsWith("docs/current/javadoc-api/")) {
                // Remove docs/current/ prefix: docs/current/javadoc-api/ -> javadoc-api/
                result = result.substring("docs/current/".length());
            }
            // Remove spurious leading 'java/' in mirrors
            if (result.startsWith("javadoc-api/java/")) {
                result = "javadoc-api/" + result.substring("javadoc-api/java/".length());
            }
        }
        
        if (isBoot) {
            // Handle various scraped path structures
            if (result.startsWith("docs/current/api/")) {
                // Remove docs/current/ prefix: docs/current/api/ -> api/
                result = result.substring("docs/current/".length());
            }
            // Remove spurious leading 'java/' in mirrors
            if (result.startsWith("api/java/")) {
                result = "api/" + result.substring("api/java/".length());
            }
        }
        
        return result;
    }
}
