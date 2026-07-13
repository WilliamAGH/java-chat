package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.config.DocsSourceRegistry.JavaApiDocumentationSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Interprets the canonical complete-Java documentation source manifest.
 *
 * <p>The resource owns source rows; this boundary enforces one byte-stable grammar before exposing
 * typed projections to the application.</p>
 */
final class JavaApiDocumentationManifest {

    private static final String MANIFEST_RESOURCE = "/java-api-documentation-sources.manifest";
    private static final String MANIFEST_DELIMITER = "|";
    private static final String MANIFEST_DELIMITER_REGEX = "\\|";
    private static final Pattern CANONICAL_UNSIGNED_INTEGER = Pattern.compile("(?:0|[1-9][0-9]*)");

    private JavaApiDocumentationManifest() {}

    static List<JavaApiDocumentationSource> load() {
        InputStream manifestStream = JavaApiDocumentationManifest.class.getResourceAsStream(MANIFEST_RESOURCE);
        if (manifestStream == null) {
            throw new IllegalStateException("Canonical Java API documentation source manifest is missing");
        }

        try {
            return parse(manifestStream);
        } catch (IOException manifestReadError) {
            throw new IllegalStateException(
                    "Canonical Java API documentation source manifest could not be read", manifestReadError);
        }
    }

    static List<JavaApiDocumentationSource> parse(InputStream manifestStream) throws IOException {
        try (BufferedReader manifestReader =
                new BufferedReader(new InputStreamReader(manifestStream, StandardCharsets.UTF_8))) {
            return parse(manifestReader.lines().toList());
        }
    }

    static List<JavaApiDocumentationSource> parse(List<String> manifestLines) {
        if (manifestLines.size() < 2) {
            throw new IllegalStateException("Canonical Java API documentation source manifest has no records");
        }
        validateHeader(manifestLines.get(0));

        List<JavaApiDocumentationSource> sources = new ArrayList<>();
        Set<String> retainedJavaReleases = new HashSet<>();
        Set<String> retainedRelativeMirrorPaths = new HashSet<>();
        for (int manifestLineIndex = 1; manifestLineIndex < manifestLines.size(); manifestLineIndex++) {
            int manifestLineNumber = manifestLineIndex + 1;
            String manifestLine = manifestLines.get(manifestLineIndex);
            if (manifestLine.isBlank()) {
                throw invalidLine(manifestLineNumber, "cannot be blank");
            }

            JavaApiDocumentationSource source = parseSource(manifestLine, manifestLineNumber);
            if (!retainedJavaReleases.add(source.javaRelease())) {
                throw invalidLine(manifestLineNumber, "duplicates Java release " + source.javaRelease());
            }
            if (!retainedRelativeMirrorPaths.add(source.relativeMirrorPath())) {
                throw invalidLine(manifestLineNumber, "duplicates mirror path " + source.relativeMirrorPath());
            }
            sources.add(source);
        }
        return List.copyOf(sources);
    }

    static int requireCanonicalUnsignedInteger(String integerText, String fieldName) {
        if (integerText == null
                || !CANONICAL_UNSIGNED_INTEGER.matcher(integerText).matches()) {
            throw new IllegalArgumentException(fieldName + " must be a canonical ASCII unsigned integer");
        }
        try {
            return Integer.parseInt(integerText);
        } catch (NumberFormatException integerOverflow) {
            throw new IllegalArgumentException(fieldName + " exceeds the supported integer range", integerOverflow);
        }
    }

    static void requireManifestText(String manifestText, String fieldName, boolean allowEmpty) {
        if (manifestText == null) {
            throw new IllegalArgumentException(fieldName + " cannot be null");
        }
        if (!allowEmpty && manifestText.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        if (!allowEmpty && hasBoundaryWhitespace(manifestText)) {
            throw new IllegalArgumentException(fieldName + " cannot have boundary whitespace");
        }
    }

    static String serialize(JavaApiDocumentationSource source) {
        return String.join(
                MANIFEST_DELIMITER,
                source.javaRelease(),
                source.remoteBaseUrl(),
                source.relativeMirrorPath(),
                source.displayName(),
                Integer.toString(source.cutDirectories()),
                Integer.toString(source.minimumHtmlFiles()),
                source.rejectRegex(),
                Boolean.toString(source.allowPartial()));
    }

    private static void validateHeader(String manifestHeader) {
        String expectedHeader = Arrays.stream(JavaApiDocumentationSource.class.getRecordComponents())
                .map(recordComponent -> recordComponent.getName())
                .reduce((leftColumn, rightColumn) -> leftColumn + MANIFEST_DELIMITER + rightColumn)
                .orElseThrow();
        if (!expectedHeader.equals(manifestHeader)) {
            throw new IllegalStateException("Canonical Java API documentation source manifest header is invalid");
        }
    }

    private static JavaApiDocumentationSource parseSource(String manifestLine, int manifestLineNumber) {
        List<String> sourceColumns = List.of(manifestLine.split(MANIFEST_DELIMITER_REGEX, -1));
        int sourceComponentCount = JavaApiDocumentationSource.class.getRecordComponents().length;
        if (sourceColumns.size() != sourceComponentCount) {
            throw invalidLine(manifestLineNumber, "has an invalid column count");
        }

        Iterator<String> sourceColumnIterator = sourceColumns.iterator();
        try {
            return new JavaApiDocumentationSource(
                    sourceColumnIterator.next(),
                    sourceColumnIterator.next(),
                    sourceColumnIterator.next(),
                    sourceColumnIterator.next(),
                    requireCanonicalUnsignedInteger(sourceColumnIterator.next(), "cutDirectories"),
                    requireCanonicalUnsignedInteger(sourceColumnIterator.next(), "minimumHtmlFiles"),
                    sourceColumnIterator.next(),
                    parseBoolean(sourceColumnIterator.next(), "allowPartial"));
        } catch (IllegalArgumentException invalidField) {
            throw new IllegalStateException(
                    "Canonical Java API documentation source manifest line " + manifestLineNumber
                            + " has an invalid field: " + invalidField.getMessage(),
                    invalidField);
        }
    }

    private static boolean parseBoolean(String booleanText, String fieldName) {
        if ("true".equals(booleanText)) {
            return true;
        }
        if ("false".equals(booleanText)) {
            return false;
        }
        throw new IllegalArgumentException(fieldName + " must be true or false");
    }

    private static boolean hasBoundaryWhitespace(String manifestText) {
        if (manifestText.isEmpty()) {
            return false;
        }
        return isAsciiWhitespace(manifestText.charAt(0))
                || isAsciiWhitespace(manifestText.charAt(manifestText.length() - 1));
    }

    private static boolean isAsciiWhitespace(char manifestCharacter) {
        return manifestCharacter == ' '
                || manifestCharacter == '\t'
                || manifestCharacter == '\r'
                || manifestCharacter == '\n'
                || manifestCharacter == '\f'
                || manifestCharacter == 0x0B;
    }

    private static IllegalStateException invalidLine(int manifestLineNumber, String problem) {
        return new IllegalStateException(
                "Canonical Java API documentation source manifest line " + manifestLineNumber + " " + problem);
    }
}
