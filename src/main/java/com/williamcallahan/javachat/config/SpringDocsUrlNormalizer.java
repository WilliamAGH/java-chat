package com.williamcallahan.javachat.config;

/**
 * Canonicalizes Spring documentation URLs so the same content resolves to a stable, shareable path.
 */
final class SpringDocsUrlNormalizer {
    private static final String SPRING_DOCS_PREFIX = "https://docs.spring.io/";
    private static final String SPRING_FRAMEWORK_SEGMENT = "spring-framework";
    private static final String SPRING_BOOT_SEGMENT = "spring-boot";
    private static final String DOCS_SEGMENT = "docs";
    private static final String CURRENT_SEGMENT = "current";
    private static final String REFERENCE_SEGMENT = "reference";
    private static final String API_SEGMENT = "api";
    private static final String JAVADOC_API_SEGMENT = "javadoc-api";
    private static final String JAVA_SEGMENT = "java";
    private static final String PATH_SEPARATOR_REGEX = "/";
    private static final String VERSION_SEPARATOR = ".";

    private static final char PATH_SEPARATOR = '/';
    private static final char VERSION_PREFIX = 'v';

    private static final int MINIMUM_PROJECT_SEGMENTS = 2;
    private static final int PROJECT_SEGMENT_INDEX = 0;
    private static final int FIRST_PATH_SEGMENT_INDEX = 1;
    private static final int URL_BUFFER_PADDING = 64;

    private static final String[] DOCS_CURRENT_REFERENCE_SEQUENCE = {
        DOCS_SEGMENT,
        CURRENT_SEGMENT,
        REFERENCE_SEGMENT
    };
    private static final String[] REFERENCE_ROOT_SEQUENCE = {REFERENCE_SEGMENT};
    private static final String[] FRAMEWORK_API_CURRENT_SEQUENCE = {
        DOCS_SEGMENT,
        CURRENT_SEGMENT,
        API_SEGMENT,
        CURRENT_SEGMENT,
        JAVADOC_API_SEGMENT
    };
    private static final String[] FRAMEWORK_API_JAVA_SEQUENCE = {
        DOCS_SEGMENT,
        CURRENT_SEGMENT,
        JAVADOC_API_SEGMENT,
        JAVA_SEGMENT
    };
    private static final String[] BOOT_API_JAVA_SEQUENCE = {
        DOCS_SEGMENT,
        CURRENT_SEGMENT,
        API_SEGMENT,
        JAVA_SEGMENT
    };

    private static final SpringDocsUrlPrefix SPRING_FRAMEWORK_REFERENCE_PREFIX = new SpringDocsUrlPrefix(
        SPRING_FRAMEWORK_SEGMENT,
        REFERENCE_SEGMENT,
        CURRENT_SEGMENT,
        null
    );
    private static final SpringDocsUrlPrefix SPRING_FRAMEWORK_JAVADOC_PREFIX = new SpringDocsUrlPrefix(
        SPRING_FRAMEWORK_SEGMENT,
        DOCS_SEGMENT,
        CURRENT_SEGMENT,
        JAVADOC_API_SEGMENT
    );
    private static final SpringDocsUrlPrefix SPRING_BOOT_REFERENCE_PREFIX = new SpringDocsUrlPrefix(
        SPRING_BOOT_SEGMENT,
        REFERENCE_SEGMENT,
        CURRENT_SEGMENT,
        null
    );
    private static final SpringDocsUrlPrefix SPRING_BOOT_API_PREFIX = new SpringDocsUrlPrefix(
        SPRING_BOOT_SEGMENT,
        DOCS_SEGMENT,
        CURRENT_SEGMENT,
        API_SEGMENT
    );

    private SpringDocsUrlNormalizer() {
    }

    static String normalize(final String url) {
        String normalizedUrl = url;
        if (url != null && url.startsWith(SPRING_DOCS_PREFIX)) {
            final String path = url.substring(SPRING_DOCS_PREFIX.length());
            final String[] segments = path.split(PATH_SEPARATOR_REGEX);
            if (segments.length >= MINIMUM_PROJECT_SEGMENTS) {
                final String projectSegment = segments[PROJECT_SEGMENT_INDEX];
                if (SPRING_FRAMEWORK_SEGMENT.equals(projectSegment)) {
                    normalizedUrl = normalizeSpringFrameworkUrl(segments, url);
                } else if (SPRING_BOOT_SEGMENT.equals(projectSegment)) {
                    normalizedUrl = normalizeSpringBootUrl(segments, url);
                }
            }
        }
        return normalizedUrl;
    }

    private static String normalizeSpringFrameworkUrl(final String[] segments, final String originalUrl) {
        String normalizedUrl = normalizeSpringFrameworkReference(segments);
        if (normalizedUrl == null) {
            normalizedUrl = normalizeSpringFrameworkReferenceRoot(segments);
        }
        if (normalizedUrl == null) {
            normalizedUrl = normalizeSpringFrameworkApiCurrent(segments);
        }
        if (normalizedUrl == null) {
            normalizedUrl = normalizeSpringFrameworkApiJava(segments);
        }
        if (normalizedUrl == null) {
            normalizedUrl = originalUrl;
        }
        return normalizedUrl;
    }

    private static String normalizeSpringBootUrl(final String[] segments, final String originalUrl) {
        String normalizedUrl = normalizeSpringBootReference(segments);
        if (normalizedUrl == null) {
            normalizedUrl = normalizeSpringBootReferenceRoot(segments);
        }
        if (normalizedUrl == null) {
            normalizedUrl = normalizeSpringBootApiJava(segments);
        }
        if (normalizedUrl == null) {
            normalizedUrl = originalUrl;
        }
        return normalizedUrl;
    }

    private static String normalizeSpringFrameworkReference(final String[] segments) {
        String normalizedUrl = null;
        if (matchesSequence(segments, FIRST_PATH_SEGMENT_INDEX, DOCS_CURRENT_REFERENCE_SEQUENCE)) {
            final int payloadIndex = FIRST_PATH_SEGMENT_INDEX + DOCS_CURRENT_REFERENCE_SEQUENCE.length;
            final int contentIndex = skipVersionSegment(segments, payloadIndex);
            normalizedUrl = buildUrl(SPRING_FRAMEWORK_REFERENCE_PREFIX, segments, contentIndex);
        }
        return normalizedUrl;
    }

    private static String normalizeSpringFrameworkReferenceRoot(final String[] segments) {
        String normalizedUrl = null;
        if (matchesSequence(segments, FIRST_PATH_SEGMENT_INDEX, REFERENCE_ROOT_SEQUENCE)) {
            final int versionIndex = FIRST_PATH_SEGMENT_INDEX + REFERENCE_ROOT_SEQUENCE.length;
            if (hasSegmentAt(segments, versionIndex) && isVersionString(segments[versionIndex])) {
                final int contentIndex = versionIndex + 1;
                normalizedUrl = buildUrl(SPRING_FRAMEWORK_REFERENCE_PREFIX, segments, contentIndex);
            }
        }
        return normalizedUrl;
    }

    private static String normalizeSpringFrameworkApiCurrent(final String[] segments) {
        String normalizedUrl = null;
        if (matchesSequence(segments, FIRST_PATH_SEGMENT_INDEX, FRAMEWORK_API_CURRENT_SEQUENCE)) {
            final int contentIndex = FIRST_PATH_SEGMENT_INDEX + FRAMEWORK_API_CURRENT_SEQUENCE.length;
            normalizedUrl = buildUrl(SPRING_FRAMEWORK_JAVADOC_PREFIX, segments, contentIndex);
        }
        return normalizedUrl;
    }

    private static String normalizeSpringFrameworkApiJava(final String[] segments) {
        String normalizedUrl = null;
        if (matchesSequence(segments, FIRST_PATH_SEGMENT_INDEX, FRAMEWORK_API_JAVA_SEQUENCE)) {
            final int contentIndex = FIRST_PATH_SEGMENT_INDEX + FRAMEWORK_API_JAVA_SEQUENCE.length;
            normalizedUrl = buildUrl(SPRING_FRAMEWORK_JAVADOC_PREFIX, segments, contentIndex);
        }
        return normalizedUrl;
    }

    private static String normalizeSpringBootReference(final String[] segments) {
        String normalizedUrl = null;
        if (matchesSequence(segments, FIRST_PATH_SEGMENT_INDEX, DOCS_CURRENT_REFERENCE_SEQUENCE)) {
            final int payloadIndex = FIRST_PATH_SEGMENT_INDEX + DOCS_CURRENT_REFERENCE_SEQUENCE.length;
            final int contentIndex = skipVersionSegment(segments, payloadIndex);
            normalizedUrl = buildUrl(SPRING_BOOT_REFERENCE_PREFIX, segments, contentIndex);
        }
        return normalizedUrl;
    }

    private static String normalizeSpringBootReferenceRoot(final String[] segments) {
        String normalizedUrl = null;
        if (matchesSequence(segments, FIRST_PATH_SEGMENT_INDEX, REFERENCE_ROOT_SEQUENCE)) {
            final int versionIndex = FIRST_PATH_SEGMENT_INDEX + REFERENCE_ROOT_SEQUENCE.length;
            if (hasSegmentAt(segments, versionIndex) && isVersionString(segments[versionIndex])) {
                final int contentIndex = versionIndex + 1;
                normalizedUrl = buildUrl(SPRING_BOOT_REFERENCE_PREFIX, segments, contentIndex);
            }
        }
        return normalizedUrl;
    }

    private static String normalizeSpringBootApiJava(final String[] segments) {
        String normalizedUrl = null;
        if (matchesSequence(segments, FIRST_PATH_SEGMENT_INDEX, BOOT_API_JAVA_SEQUENCE)) {
            final int contentIndex = FIRST_PATH_SEGMENT_INDEX + BOOT_API_JAVA_SEQUENCE.length;
            normalizedUrl = buildUrl(SPRING_BOOT_API_PREFIX, segments, contentIndex);
        }
        return normalizedUrl;
    }

    private static String buildUrl(
        final SpringDocsUrlPrefix prefix,
        final String[] segments,
        final int startIndex
    ) {
        final StringBuilder urlBuilder = new StringBuilder(SPRING_DOCS_PREFIX.length() + URL_BUFFER_PADDING);
        urlBuilder.append(SPRING_DOCS_PREFIX)
            .append(prefix.projectSegment())
            .append(PATH_SEPARATOR)
            .append(prefix.primarySegment())
            .append(PATH_SEPARATOR)
            .append(prefix.secondarySegment());
        if (prefix.tertiarySegment() != null) {
            urlBuilder.append(PATH_SEPARATOR).append(prefix.tertiarySegment());
        }
        for (int segmentIndex = startIndex; segmentIndex < segments.length; segmentIndex++) {
            urlBuilder.append(PATH_SEPARATOR).append(segments[segmentIndex]);
        }
        return urlBuilder.toString();
    }

    private static boolean matchesSequence(
        final String[] segments,
        final int startIndex,
        final String[] expectedSegments
    ) {
        final int expectedLength = expectedSegments.length;
        boolean matches = false;
        if (segments.length >= startIndex + expectedLength) {
            matches = true;
            for (int offsetIndex = 0; offsetIndex < expectedLength; offsetIndex++) {
                final String expectedSegment = expectedSegments[offsetIndex];
                if (!expectedSegment.equals(segments[startIndex + offsetIndex])) {
                    matches = false;
                    break;
                }
            }
        }
        return matches;
    }

    private static int skipVersionSegment(final String[] segments, final int startIndex) {
        int adjustedIndex = startIndex;
        if (hasSegmentAt(segments, startIndex) && isVersionString(segments[startIndex])) {
            adjustedIndex = startIndex + 1;
        }
        return adjustedIndex;
    }

    private static boolean hasSegmentAt(final String[] segments, final int segmentIndex) {
        return segments.length > segmentIndex;
    }

    private static boolean isVersionString(final String text) {
        boolean isVersion = false;
        if (text != null && !text.isEmpty()) {
            final char firstChar = text.charAt(0);
            isVersion = text.contains(VERSION_SEPARATOR)
                && (Character.isDigit(firstChar) || firstChar == VERSION_PREFIX);
        }
        return isVersion;
    }

    private record SpringDocsUrlPrefix(
        String projectSegment,
        String primarySegment,
        String secondarySegment,
        String tertiarySegment
    ) {
    }
}
