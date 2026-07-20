package com.williamcallahan.javachat.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts encounter-ordered Java release references from learner queries. */
public final class QueryVersionExtractor {
    private static final Pattern EXPLICIT_VERSION_PATTERN =
            Pattern.compile("\\b(?:java\\s*se|javase|java|jdk)[\\s-]*(\\d{1,2})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORTHAND_VERSION_PATTERN = Pattern.compile(
            "^\\s*(?:,|and\\b|vs(?:\\.|\\b)|versus\\b|\\+|/|&)\\s*(\\d{1,2})\\b", Pattern.CASE_INSENSITIVE);

    private QueryVersionExtractor() {}

    /**
     * Returns every explicitly requested Java release in encounter order without duplicates.
     *
     * <p>After an explicit Java or JDK reference, comparison shorthand such as {@code Java 21/24}
     * and {@code JDK 21 vs 24} remains version-bearing until the connector chain ends.</p>
     *
     * @param query learner query
     * @param supportedShorthandVersions releases that an unprefixed comparison token may represent
     * @return immutable release tokens such as {@code [21, 24]}
     */
    public static List<String> extractVersionNumbers(String query, List<String> supportedShorthandVersions) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> retainedVersions = new LinkedHashSet<>();
        Matcher explicitVersionMatcher = EXPLICIT_VERSION_PATTERN.matcher(query);
        while (explicitVersionMatcher.find()) {
            retainedVersions.add(explicitVersionMatcher.group(1));
            collectShorthandVersions(query, explicitVersionMatcher.end(), retainedVersions, supportedShorthandVersions);
        }
        return List.copyOf(retainedVersions);
    }

    /**
     * Prepends every requested release so semantic search retains comparison intent.
     *
     * @param query original learner query
     * @param requestedVersions releases already extracted from the query
     * @return boosted query, or the original query when no Java release is named
     */
    public static String boostQueryWithVersionContext(String query, List<String> requestedVersions) {
        if (requestedVersions.isEmpty()) {
            return query;
        }
        List<String> versionContexts = new ArrayList<>(requestedVersions.size());
        for (String requestedVersion : requestedVersions) {
            versionContexts.add("JDK " + requestedVersion + " Java SE " + requestedVersion + " Java " + requestedVersion
                    + " release documentation");
        }
        return String.join("; ", versionContexts) + ": " + query;
    }

    private static void collectShorthandVersions(
            String query,
            int explicitVersionEnd,
            Set<String> retainedVersions,
            List<String> supportedShorthandVersions) {
        int comparisonCursor = explicitVersionEnd;
        while (comparisonCursor < query.length()) {
            Matcher shorthandVersionMatcher = SHORTHAND_VERSION_PATTERN.matcher(query.substring(comparisonCursor));
            if (!shorthandVersionMatcher.find()) {
                return;
            }
            String shorthandVersion = shorthandVersionMatcher.group(1);
            if (!supportedShorthandVersions.contains(shorthandVersion)) {
                return;
            }
            retainedVersions.add(shorthandVersion);
            comparisonCursor += shorthandVersionMatcher.end();
        }
    }
}
