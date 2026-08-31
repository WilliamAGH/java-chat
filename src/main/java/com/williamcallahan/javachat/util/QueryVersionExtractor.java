package com.williamcallahan.javachat.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts encounter-ordered Java release references from learner queries. */
public final class QueryVersionExtractor {
    private static final Pattern EXPLICIT_VERSION_PATTERN =
            Pattern.compile("\\b(?:java\\s*se|javase|java|jdk)[\\s-]*(\\d{1,2})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORTHAND_VERSION_PATTERN = Pattern.compile(
            "^\\s*(,|and\\b|vs(?:\\.|\\b)|versus\\b|\\+|/|&)\\s*(\\d{1,2})\\b", Pattern.CASE_INSENSITIVE);

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

    /**
     * Resolves each requested Java release to exact or adjacent indexed evidence.
     *
     * <p>An exact indexed release remains exact. A gap uses the nearest lower and higher releases;
     * requests outside the indexed range use the nearest available edge.</p>
     *
     * @param requestedVersions releases named by the learner
     * @param indexedVersions releases available in the Java documentation corpus
     * @return encounter-ordered indexed releases that can evidence the request
     */
    public static List<String> resolveEvidenceVersions(List<String> requestedVersions, List<String> indexedVersions) {
        Set<String> evidenceVersions = new LinkedHashSet<>();
        for (VersionEvidence versionEvidence : resolveVersionEvidence(requestedVersions, indexedVersions)) {
            evidenceVersions.addAll(versionEvidence.evidenceVersions());
        }
        return List.copyOf(evidenceVersions);
    }

    /** Resolves each requested release independently so prompt attribution retains the mapping. */
    public static List<VersionEvidence> resolveVersionEvidence(
            List<String> requestedVersions, List<String> indexedVersions) {
        NavigableSet<Integer> indexedReleases = new TreeSet<>();
        indexedVersions.stream().map(Integer::parseInt).forEach(indexedReleases::add);
        if (indexedReleases.isEmpty()) {
            return List.of();
        }
        List<VersionEvidence> versionEvidence = new ArrayList<>(requestedVersions.size());
        for (String requestedVersion : requestedVersions) {
            int requestedRelease = Integer.parseInt(requestedVersion);
            Integer lowerEvidence =
                    Objects.requireNonNullElse(indexedReleases.floor(requestedRelease), indexedReleases.first());
            Integer higherEvidence =
                    Objects.requireNonNullElse(indexedReleases.ceiling(requestedRelease), indexedReleases.last());
            Set<String> requestEvidenceVersions = new LinkedHashSet<>();
            requestEvidenceVersions.add(lowerEvidence.toString());
            requestEvidenceVersions.add(higherEvidence.toString());
            versionEvidence.add(new VersionEvidence(requestedVersion, List.copyOf(requestEvidenceVersions)));
        }
        return List.copyOf(versionEvidence);
    }

    /** Preserves one requested release and the exact indexed releases selected as its evidence. */
    public record VersionEvidence(String requestedVersion, List<String> evidenceVersions) {
        public VersionEvidence {
            Objects.requireNonNull(requestedVersion, "requestedVersion");
            evidenceVersions = List.copyOf(evidenceVersions);
        }

        /** Returns whether the corpus contains the requested release exactly. */
        public boolean isExact() {
            return evidenceVersions.equals(List.of(requestedVersion));
        }
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
            String comparisonConnector = shorthandVersionMatcher.group(1);
            String shorthandVersion = shorthandVersionMatcher.group(2);
            boolean requiresIndexedRelease =
                    ",".equals(comparisonConnector) || "and".equalsIgnoreCase(comparisonConnector);
            if (requiresIndexedRelease && !supportedShorthandVersions.contains(shorthandVersion)) {
                return;
            }
            retainedVersions.add(shorthandVersion);
            comparisonCursor += shorthandVersionMatcher.end();
        }
    }
}
