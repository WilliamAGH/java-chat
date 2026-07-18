package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.config.DocsSourceRegistry.DocumentationSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Interprets the canonical non-Java-API documentation source manifest.
 *
 * <p>The source manifest header owns the record names and order shared by Java and Bash. This
 * boundary rejects any projection drift before exposing validated sources to the application.</p>
 */
final class DocumentationSourceManifest {

    private static final String SOURCE_MANIFEST_RESOURCE = "/documentation-sources.manifest";
    private static final String MANIFEST_DELIMITER = "|";
    private static final String MANIFEST_DELIMITER_REGEX = "\\|";
    private static final int FIRST_MANIFEST_RECORD_LINE_NUMBER = 2;
    private static final int MINIMUM_MANIFEST_LINE_COUNT = FIRST_MANIFEST_RECORD_LINE_NUMBER;

    private static final String CANONICAL_MANIFEST_HEADER = loadCanonicalManifestHeader();
    private static final List<String> CANONICAL_MANIFEST_FIELDS = validateCanonicalManifestHeader();
    private static final List<RecordComponent> DOCUMENTATION_SOURCE_COMPONENTS = verifyDocumentationSourceProjection();
    private static final Constructor<DocumentationSource> DOCUMENTATION_SOURCE_CONSTRUCTOR =
            resolveDocumentationSourceConstructor();

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

    private static String loadCanonicalManifestHeader() {
        InputStream manifestStream = DocumentationSourceManifest.class.getResourceAsStream(SOURCE_MANIFEST_RESOURCE);
        if (manifestStream == null) {
            throw new IllegalStateException("Canonical documentation source manifest is missing");
        }

        try (BufferedReader manifestReader =
                new BufferedReader(new InputStreamReader(manifestStream, StandardCharsets.UTF_8))) {
            String manifestHeader = manifestReader.readLine();
            if (manifestHeader == null) {
                throw new IllegalStateException("Canonical documentation source manifest is empty");
            }
            return manifestHeader;
        } catch (IOException manifestReadError) {
            throw new IllegalStateException(
                    "Canonical documentation source manifest header could not be read", manifestReadError);
        }
    }

    private static List<String> validateCanonicalManifestHeader() {
        List<String> canonicalManifestFields = List.of(CANONICAL_MANIFEST_HEADER.split(MANIFEST_DELIMITER_REGEX, -1));
        Set<String> retainedCanonicalFields = new HashSet<>();
        for (int fieldIndex = 0; fieldIndex < canonicalManifestFields.size(); fieldIndex++) {
            String canonicalFieldName = canonicalManifestFields.get(fieldIndex);
            if (canonicalFieldName.isBlank()
                    || !canonicalFieldName.equals(canonicalFieldName.strip())
                    || canonicalFieldName.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalStateException(
                        "Canonical documentation source manifest header field " + (fieldIndex + 1) + " is invalid");
            }
            if (!retainedCanonicalFields.add(canonicalFieldName)) {
                throw new IllegalStateException(
                        "Canonical documentation source manifest header duplicates " + canonicalFieldName);
            }
        }
        return List.copyOf(canonicalManifestFields);
    }

    private static List<RecordComponent> verifyDocumentationSourceProjection() {
        List<RecordComponent> documentationSourceComponents = List.of(DocumentationSource.class.getRecordComponents());
        List<String> projectedFieldNames = documentationSourceComponents.stream()
                .map(RecordComponent::getName)
                .toList();
        if (!CANONICAL_MANIFEST_FIELDS.equals(projectedFieldNames)) {
            throw new IllegalStateException(
                    "DocumentationSource record must exactly project the canonical manifest header");
        }
        return documentationSourceComponents;
    }

    private static Constructor<DocumentationSource> resolveDocumentationSourceConstructor() {
        Class<?>[] componentTypes = DOCUMENTATION_SOURCE_COMPONENTS.stream()
                .map(RecordComponent::getType)
                .toArray(Class<?>[]::new);
        try {
            return DocumentationSource.class.getDeclaredConstructor(componentTypes);
        } catch (NoSuchMethodException missingCanonicalConstructor) {
            throw new IllegalStateException(
                    "DocumentationSource canonical constructor does not match its manifest projection",
                    missingCanonicalConstructor);
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
            Object[] constructorArguments = DOCUMENTATION_SOURCE_COMPONENTS.stream()
                    .map(manifestColumns::nextComponent)
                    .toArray();
            return instantiateDocumentationSource(constructorArguments);
        } catch (IllegalArgumentException invalidField) {
            throw new IllegalStateException(
                    "Canonical documentation source manifest line "
                            + manifestLineNumber
                            + " has an invalid field: "
                            + invalidField.getMessage(),
                    invalidField);
        }
    }

    private static DocumentationSource instantiateDocumentationSource(Object[] constructorArguments) {
        try {
            return DOCUMENTATION_SOURCE_CONSTRUCTOR.newInstance(constructorArguments);
        } catch (InvocationTargetException invalidSource) {
            if (invalidSource.getCause() instanceof IllegalArgumentException invalidField) {
                throw invalidField;
            }
            throw new IllegalStateException(
                    "DocumentationSource canonical constructor failed", invalidSource.getCause());
        } catch (ReflectiveOperationException projectionFailure) {
            throw new IllegalStateException(
                    "DocumentationSource manifest projection could not be created", projectionFailure);
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

    /** Reads manifest columns in the canonical record-component order. */
    private static final class ManifestColumnCursor {

        private final List<String> sourceColumns;
        private int nextColumnIndex;

        private ManifestColumnCursor(List<String> sourceColumns) {
            this.sourceColumns = sourceColumns;
        }

        private Object nextComponent(RecordComponent recordComponent) {
            String manifestText = sourceColumns.get(nextColumnIndex);
            nextColumnIndex++;
            if (recordComponent.getType() == String.class) {
                return manifestText;
            }
            if (recordComponent.getType() == int.class) {
                return DocumentationManifestFieldRules.requireCanonicalUnsignedInteger(
                        manifestText, recordComponent.getName());
            }
            if (recordComponent.getType() == boolean.class) {
                return DocumentationManifestFieldRules.requireBoolean(manifestText, recordComponent.getName());
            }
            throw new IllegalStateException(
                    "DocumentationSource contains unsupported manifest component " + recordComponent.getName());
        }
    }
}
