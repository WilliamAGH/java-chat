package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.config.DocsSourceRegistry.DocumentationSource;
import com.williamcallahan.javachat.config.DocsSourceRegistry.DocumentationSource.DocumentationMirrorPolicy;
import com.williamcallahan.javachat.config.DocsSourceRegistry.DocumentationSource.DocumentationSourceLifecycle;
import com.williamcallahan.javachat.config.DocsSourceRegistry.DocumentationSource.DocumentationSourceLocation;
import com.williamcallahan.javachat.config.DocsSourceRegistry.DocumentationSource.DocumentationSourceMetadata;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Interprets the canonical non-Java-API documentation source manifest.
 *
 * <p>The source manifest owns the official source inventory, while the field catalog owns the
 * record names and order shared by Java and Bash. This boundary rejects any projection drift before
 * exposing validated sources to the application.</p>
 */
final class DocumentationSourceManifest {

    private static final String SOURCE_MANIFEST_RESOURCE = "/documentation-sources.manifest";
    private static final String FIELD_CATALOG_RESOURCE = "/documentation-source-fields.manifest";
    private static final String MANIFEST_DELIMITER = "|";
    private static final String MANIFEST_DELIMITER_REGEX = "\\|";
    private static final int FIRST_MANIFEST_RECORD_LINE_NUMBER = 2;
    private static final int MINIMUM_MANIFEST_LINE_COUNT = FIRST_MANIFEST_RECORD_LINE_NUMBER;

    private static final List<String> CANONICAL_MANIFEST_FIELDS = loadCanonicalManifestFields();
    private static final List<RecordComponent> DOCUMENTATION_SOURCE_COMPONENTS = verifyDocumentationSourceProjections();
    private static final String CANONICAL_MANIFEST_HEADER = String.join(MANIFEST_DELIMITER, CANONICAL_MANIFEST_FIELDS);

    private DocumentationSourceManifest() {}

    static List<DocumentationSource> load() {
        InputStream manifestStream = DocumentationSourceManifest.class.getResourceAsStream(SOURCE_MANIFEST_RESOURCE);
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
        Set<String> retainedDocSets = new HashSet<>();
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
            if (!retainedDocSets.add(documentationSource.docSet())) {
                throw invalidLine(manifestLineNumber, "duplicates docSet " + documentationSource.docSet());
            }
            documentationSources.add(documentationSource);
        }
        validateLifecycleMirrorPaths(documentationSources);
        return List.copyOf(documentationSources);
    }

    static List<String> canonicalManifestFields() {
        return CANONICAL_MANIFEST_FIELDS;
    }

    static String canonicalManifestHeader() {
        return CANONICAL_MANIFEST_HEADER;
    }

    static String serialize(DocumentationSource documentationSource) {
        return DOCUMENTATION_SOURCE_COMPONENTS.stream()
                .map(recordComponent -> readManifestComponent(recordComponent, documentationSource))
                .collect(Collectors.joining(MANIFEST_DELIMITER));
    }

    private static List<String> loadCanonicalManifestFields() {
        InputStream fieldCatalogStream = DocumentationSourceManifest.class.getResourceAsStream(FIELD_CATALOG_RESOURCE);
        if (fieldCatalogStream == null) {
            throw new IllegalStateException("Canonical documentation source field catalog is missing");
        }

        try (BufferedReader fieldCatalogReader =
                new BufferedReader(new InputStreamReader(fieldCatalogStream, StandardCharsets.UTF_8))) {
            return validateCanonicalManifestFields(fieldCatalogReader.lines().toList());
        } catch (IOException fieldCatalogReadError) {
            throw new IllegalStateException(
                    "Canonical documentation source field catalog could not be read", fieldCatalogReadError);
        }
    }

    private static List<String> validateCanonicalManifestFields(List<String> canonicalManifestFields) {
        if (canonicalManifestFields.isEmpty()) {
            throw new IllegalStateException("Canonical documentation source field catalog has no records");
        }

        Set<String> retainedCanonicalFields = new HashSet<>();
        for (int fieldIndex = 0; fieldIndex < canonicalManifestFields.size(); fieldIndex++) {
            String canonicalFieldName = canonicalManifestFields.get(fieldIndex);
            int catalogLineNumber = fieldIndex + 1;
            if (canonicalFieldName.isBlank()
                    || !canonicalFieldName.equals(canonicalFieldName.strip())
                    || canonicalFieldName.contains(MANIFEST_DELIMITER)
                    || canonicalFieldName.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalStateException(
                        "Canonical documentation source field catalog line " + catalogLineNumber + " is invalid");
            }
            if (!retainedCanonicalFields.add(canonicalFieldName)) {
                throw new IllegalStateException("Canonical documentation source field catalog line "
                        + catalogLineNumber
                        + " duplicates "
                        + canonicalFieldName);
            }
        }
        return List.copyOf(canonicalManifestFields);
    }

    private static List<RecordComponent> verifyDocumentationSourceProjections() {
        List<RecordComponent> documentationSourceComponents = List.of(DocumentationSource.class.getRecordComponents());
        verifyFieldProjection("DocumentationSource record", documentationSourceComponents);

        List<Class<?>> manifestParameterGroupTypes = List.of(
                DocumentationSourceLocation.class,
                DocumentationSourceMetadata.class,
                DocumentationMirrorPolicy.class,
                DocumentationSourceLifecycle.class);
        List<RecordComponent> manifestParameterComponents = manifestParameterGroupTypes.stream()
                .flatMap(parameterGroupType -> Arrays.stream(parameterGroupType.getRecordComponents()))
                .toList();
        verifyFieldProjection("DocumentationSource manifest parameter groups", manifestParameterComponents);
        return documentationSourceComponents;
    }

    private static void verifyFieldProjection(String projectionName, List<RecordComponent> projectedComponents) {
        List<String> projectedFieldNames =
                projectedComponents.stream().map(RecordComponent::getName).toList();
        if (!CANONICAL_MANIFEST_FIELDS.equals(projectedFieldNames)) {
            throw new IllegalStateException(
                    projectionName + " must exactly project the canonical documentation source field catalog");
        }
    }

    private static void validateHeader(String manifestHeader) {
        if (!CANONICAL_MANIFEST_HEADER.equals(manifestHeader)) {
            throw new IllegalStateException("Canonical documentation source manifest header is invalid");
        }
    }

    private static DocumentationSource parseSource(String manifestLine, int manifestLineNumber) {
        List<String> sourceColumns = List.of(manifestLine.split(MANIFEST_DELIMITER_REGEX, -1));
        if (sourceColumns.size() != CANONICAL_MANIFEST_FIELDS.size()) {
            throw invalidLine(manifestLineNumber, "has an invalid column count");
        }

        ManifestColumnCursor manifestColumns = new ManifestColumnCursor(sourceColumns);
        try {
            DocumentationSourceLocation sourceLocation = new DocumentationSourceLocation(
                    manifestColumns.nextText(), manifestColumns.nextText(), manifestColumns.nextText());
            DocumentationSourceMetadata sourceMetadata = new DocumentationSourceMetadata(
                    manifestColumns.nextText(),
                    manifestColumns.nextText(),
                    manifestColumns.nextText(),
                    manifestColumns.nextText(),
                    manifestColumns.nextText());
            DocumentationMirrorPolicy mirrorPolicy = new DocumentationMirrorPolicy(
                    manifestColumns.nextCanonicalUnsignedInteger(),
                    manifestColumns.nextText(),
                    manifestColumns.nextBoolean());
            DocumentationSourceLifecycle sourceLifecycle = new DocumentationSourceLifecycle(
                    manifestColumns.nextText(),
                    manifestColumns.nextText(),
                    manifestColumns.nextText(),
                    manifestColumns.nextText());
            manifestColumns.requireFullyConsumed();
            return DocumentationSource.fromManifestProjection(
                    sourceLocation, sourceMetadata, mirrorPolicy, sourceLifecycle);
        } catch (IllegalArgumentException invalidField) {
            throw new IllegalStateException(
                    "Canonical documentation source manifest line "
                            + manifestLineNumber
                            + " has an invalid field: "
                            + invalidField.getMessage(),
                    invalidField);
        }
    }

    private static void validateLifecycleMirrorPaths(List<DocumentationSource> documentationSources) {
        Set<String> retainedSupersededRelativeMirrorPaths = new HashSet<>();
        for (int sourceIndex = 0; sourceIndex < documentationSources.size(); sourceIndex++) {
            DocumentationSource documentationSource = documentationSources.get(sourceIndex);
            String supersededRelativeMirrorPath = documentationSource.supersededRelativeMirrorPath();
            if (supersededRelativeMirrorPath.isEmpty()) {
                continue;
            }
            int manifestLineNumber = sourceIndex + FIRST_MANIFEST_RECORD_LINE_NUMBER;
            if (!retainedSupersededRelativeMirrorPaths.add(supersededRelativeMirrorPath)) {
                throw invalidLine(
                        manifestLineNumber, "duplicates superseded mirror path " + supersededRelativeMirrorPath);
            }
            for (int activeSourceIndex = 0; activeSourceIndex < documentationSources.size(); activeSourceIndex++) {
                if (activeSourceIndex == sourceIndex) {
                    continue;
                }
                String activeRelativeMirrorPath =
                        documentationSources.get(activeSourceIndex).relativeMirrorPath();
                if (DocumentationSource.mirrorRootsOverlap(supersededRelativeMirrorPath, activeRelativeMirrorPath)) {
                    throw invalidLine(
                            manifestLineNumber,
                            "superseded mirror path "
                                    + supersededRelativeMirrorPath
                                    + " overlaps active mirror path "
                                    + activeRelativeMirrorPath);
                }
            }
        }
    }

    private static String readManifestComponent(
            RecordComponent recordComponent, DocumentationSource documentationSource) {
        try {
            return String.valueOf(recordComponent.getAccessor().invoke(documentationSource));
        } catch (ReflectiveOperationException componentReadError) {
            throw new IllegalStateException(
                    "DocumentationSource record projection could not be serialized", componentReadError);
        }
    }

    private static IllegalStateException invalidLine(int manifestLineNumber, String problem) {
        return new IllegalStateException(
                "Canonical documentation source manifest line " + manifestLineNumber + " " + problem);
    }

    /** Reads manifest columns in the canonical field-catalog order without positional constructor calls. */
    private static final class ManifestColumnCursor {

        private final List<String> sourceColumns;
        private int nextColumnIndex;

        private ManifestColumnCursor(List<String> sourceColumns) {
            this.sourceColumns = sourceColumns;
        }

        private String nextText() {
            String manifestText = sourceColumns.get(nextColumnIndex);
            nextColumnIndex++;
            return manifestText;
        }

        private int nextCanonicalUnsignedInteger() {
            String canonicalFieldName = currentCanonicalFieldName();
            return DocumentationManifestFieldRules.requireCanonicalUnsignedInteger(nextText(), canonicalFieldName);
        }

        private boolean nextBoolean() {
            String canonicalFieldName = currentCanonicalFieldName();
            return DocumentationManifestFieldRules.requireBoolean(nextText(), canonicalFieldName);
        }

        private String currentCanonicalFieldName() {
            return CANONICAL_MANIFEST_FIELDS.get(nextColumnIndex);
        }

        private void requireFullyConsumed() {
            if (nextColumnIndex != sourceColumns.size()) {
                throw new IllegalStateException("Documentation source manifest projection left unread columns");
            }
        }
    }
}
