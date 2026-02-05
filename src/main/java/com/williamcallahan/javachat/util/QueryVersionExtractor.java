package com.williamcallahan.javachat.util;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts Java version information from user queries for version-aware retrieval.
 * Detects patterns like "Java 25", "JDK 24", "java25" and returns normalized version identifiers.
 */
public final class QueryVersionExtractor {
    private QueryVersionExtractor() {}

    /**
     * Pattern to match Java version references in queries.
     * Matches: "Java 25", "JDK 24", "java25", "jdk-25", "Java SE 24", "JavaSE 25"
     */
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("\\b(?:java\\s*se|javase|java|jdk)[\\s-]*(\\d{1,2})\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Extract the Java version number from a query string.
     *
     * @param query the user's query text
     * @return Optional containing the version number (e.g., "25") if detected, empty otherwise
     */
    public static Optional<String> extractVersionNumber(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = VERSION_PATTERN.matcher(query);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    /**
     * Extract a normalized source identifier from a query string.
     * Returns identifiers like "java25", "java24" that match URL path patterns.
     *
     * @param query the user's query text
     * @return Optional containing the source identifier if detected, empty otherwise
     */
    public static Optional<String> extractSourceIdentifier(String query) {
        return extractVersionNumber(query).map(version -> "java" + version);
    }

    /**
     * Build URL filter patterns for a detected version.
     * Returns patterns that can match against document URLs.
     *
     * @param query the user's query text
     * @return Optional containing filter patterns if version detected, empty otherwise
     */
    public static Optional<VersionFilterPatterns> extractFilterPatterns(String query) {
        return extractVersionNumber(query).map(VersionFilterPatterns::new);
    }

    /**
     * Boost a query by prepending version context for better semantic matching.
     * Transforms "What's new in Java 25" to "JDK 25 Java 25 release features: What's new in Java 25"
     *
     * @param query the original query
     * @return boosted query with version context, or original if no version detected
     */
    public static String boostQueryWithVersionContext(String query) {
        Optional<String> version = extractVersionNumber(query);
        if (version.isEmpty()) {
            return query;
        }
        String v = version.get();
        return String.format("JDK %s Java SE %s Java %s release features documentation: %s", v, v, v, query);
    }

    /**
     * Container for URL filter patterns derived from a version number.
     */
    public static final class VersionFilterPatterns {
        private final String versionNumber;
        private final List<String> urlTokens;
        private final List<String> textTokens;

        VersionFilterPatterns(String versionNumber) {
            this.versionNumber = versionNumber;
            this.urlTokens = buildUrlTokens(versionNumber);
            this.textTokens = buildTextTokens(versionNumber);
        }

        public String versionNumber() {
            return versionNumber;
        }

        /**
         * Check if a URL matches any of the version patterns.
         *
         * @param url the document URL to check
         * @return true if URL contains version-specific patterns
         */
        public boolean matchesUrl(String url) {
            return matchesAny(url, urlTokens);
        }

        /**
         * Check if metadata (URL/title) matches any version patterns.
         *
         * @param url the document URL to check
         * @param title the document title to check
         * @return true if URL or title contains version-specific patterns
         */
        public boolean matchesMetadata(String url, String title) {
            return matchesUrl(url) || matchesText(title);
        }

        private boolean matchesText(String text) {
            return matchesAny(text, textTokens);
        }

        private static boolean matchesAny(String text, List<String> tokens) {
            if (text == null || tokens == null || tokens.isEmpty()) {
                return false;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            for (String token : tokens) {
                if (lower.contains(token)) {
                    return true;
                }
            }
            return false;
        }

        private static List<String> buildUrlTokens(String versionNumber) {
            String v = versionNumber;
            return List.of(
                    "java" + v,
                    "jdk" + v,
                    "java-" + v,
                    "jdk-" + v,
                    "/javase/" + v,
                    "/java/javase/" + v,
                    "/java/se/" + v);
        }

        private static List<String> buildTextTokens(String versionNumber) {
            String v = versionNumber;
            return List.of("java se " + v, "jdk " + v);
        }
    }
}
