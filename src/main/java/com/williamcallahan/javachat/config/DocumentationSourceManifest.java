package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.config.DocsSourceRegistry.DocumentationSource;
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

/**
 * Interprets the canonical non-Java-API documentation source manifest.
 *
 * <p>The resource owns the official source inventory while this boundary enforces its stable
 * grammar before exposing typed projections to the application.</p>
 */
final class DocumentationSourceManifest {

    private static final String MANIFEST_RESOURCE = "/documentation-sources.manifest";
    private static final String MANIFEST_DELIMITER = "|";
    private static final String MANIFEST_DELIMITER_REGEX = "\\|";
    private static final int MINIMUM_MANIFEST_LINE_COUNT = 2;

    private DocumentationSourceManifest() {}

    static List<DocumentationSource> load() {
        InputStream manifestStream = DocumentationSourceManifest.class.getResourceAsStream(MANIFEST_RESOURCE);
        if (manifestStream == null) {
            throw new IllegalStateException("Canonical documentation source manifest is missing");
        }

        try {
            return parse(manifestStream);
        } catch (IOException manifestReadError) {
            throw new IllegalStateException(
                    "Canonical documentation source manifest could not be read", manifestReadError);
        }
    }

    static List<DocumentationSource> parse(InputStream manifestStream) throws IOException {
        try (BufferedReader manifestReader =
                new BufferedReader(new InputStreamReader(manifestStream, StandardCharsets.UTF_8))) {
            return parse(manifestReader.lines().toList());
        }
    }

    static List<DocumentationSource> parse(List<String> manifestLines) {
        if (manifestLines.size() < MINIMUM_MANIFEST_LINE_COUNT) {
            throw new IllegalStateException("Canonical documentation source manifest has no records");
        }
        validateHeader(manifestLines.getFirst());

        List<DocumentationSource> documentationSources = new ArrayList<>();
        Set<String> retainedRelativeMirrorPaths = new HashSet<>();
        for (int manifestLineIndex = 1; manifestLineIndex < manifestLines.size(); manifestLineIndex++) {
            int manifestLineNumber = manifestLineIndex + 1;
            String manifestLine = manifestLines.get(manifestLineIndex);
            if (manifestLine.isBlank()) {
                throw invalidLine(manifestLineNumber, "cannot be blank");
            }

            DocumentationSource documentationSource = parseSource(manifestLine, manifestLineNumber);
            if (!retainedRelativeMirrorPaths.add(documentationSource.relativeMirrorPath())) {
                throw invalidLine(
                        manifestLineNumber, "duplicates mirror path " + documentationSource.relativeMirrorPath());
            }
            documentationSources.add(documentationSource);
        }
        return List.copyOf(documentationSources);
    }

    static String serialize(DocumentationSource documentationSource) {
        return String.join(
                MANIFEST_DELIMITER,
                documentationSource.fetchUrl(),
                documentationSource.citationBaseUrl(),
                documentationSource.relativeMirrorPath(),
                documentationSource.displayName(),
                documentationSource.docSet(),
                documentationSource.sourceKind(),
                documentationSource.docType(),
                documentationSource.docVersion());
    }

    private static void validateHeader(String manifestHeader) {
        String expectedHeader = Arrays.stream(DocumentationSource.class.getRecordComponents())
                .map(recordComponent -> recordComponent.getName())
                .reduce((leftColumn, rightColumn) -> leftColumn + MANIFEST_DELIMITER + rightColumn)
                .orElseThrow();
        if (!expectedHeader.equals(manifestHeader)) {
            throw new IllegalStateException("Canonical documentation source manifest header is invalid");
        }
    }

    private static DocumentationSource parseSource(String manifestLine, int manifestLineNumber) {
        List<String> sourceColumns = List.of(manifestLine.split(MANIFEST_DELIMITER_REGEX, -1));
        int sourceComponentCount = DocumentationSource.class.getRecordComponents().length;
        if (sourceColumns.size() != sourceComponentCount) {
            throw invalidLine(manifestLineNumber, "has an invalid column count");
        }

        Iterator<String> sourceColumnIterator = sourceColumns.iterator();
        try {
            return new DocumentationSource(
                    sourceColumnIterator.next(),
                    sourceColumnIterator.next(),
                    sourceColumnIterator.next(),
                    sourceColumnIterator.next(),
                    sourceColumnIterator.next(),
                    sourceColumnIterator.next(),
                    sourceColumnIterator.next(),
                    sourceColumnIterator.next());
        } catch (IllegalArgumentException invalidField) {
            throw new IllegalStateException(
                    "Canonical documentation source manifest line "
                            + manifestLineNumber
                            + " has an invalid field: "
                            + invalidField.getMessage(),
                    invalidField);
        }
    }

    private static IllegalStateException invalidLine(int manifestLineNumber, String problem) {
        return new IllegalStateException(
                "Canonical documentation source manifest line " + manifestLineNumber + " " + problem);
    }
}
