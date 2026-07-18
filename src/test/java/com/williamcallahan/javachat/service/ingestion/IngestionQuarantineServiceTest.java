package com.williamcallahan.javachat.service.ingestion;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies rejected documents are copied safely without mutating their canonical source mirror. */
class IngestionQuarantineServiceTest {
    private static final String QUARANTINE_DIRECTORY_NAME = ".quarantine";
    private static final Pattern TIMESTAMPED_STREAMS_DOCUMENT = Pattern.compile("streams\\.\\d{8}_\\d{6}\\.html");
    private static final Pattern TIMESTAMPED_LANDING_DOCUMENT = Pattern.compile("landing\\.\\d{8}_\\d{6}\\.html");

    @Test
    void copiesDocumentInsideDocumentationRootToSiblingQuarantineWithRelativePath(@TempDir Path temporaryDirectory)
            throws IOException {
        Path documentationRoot = temporaryDirectory.resolve("data/docs");
        Path canonicalDocument = documentationRoot.resolve("java/io/streams.html");
        byte[] canonicalDocumentBytes = "canonical streams page".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(Objects.requireNonNull(canonicalDocument.getParent(), "canonical document parent"));
        Files.write(canonicalDocument, canonicalDocumentBytes);

        IngestionQuarantineService quarantineService = new IngestionQuarantineService(documentationRoot);
        IngestionQuarantineService.QuarantineResult quarantineResult = quarantineService.quarantine(canonicalDocument);

        Path quarantineRoot = Objects.requireNonNull(documentationRoot.getParent(), "documentation root parent")
                .resolve(QUARANTINE_DIRECTORY_NAME)
                .toAbsolutePath()
                .normalize();
        Path inspectionCopy = quarantineResult.quarantined();
        assertEquals(canonicalDocument.toAbsolutePath().normalize(), quarantineResult.original());
        assertTrue(Files.exists(canonicalDocument));
        assertArrayEquals(canonicalDocumentBytes, Files.readAllBytes(canonicalDocument));
        assertTrue(inspectionCopy.startsWith(quarantineRoot));
        assertFalse(inspectionCopy.startsWith(documentationRoot.toAbsolutePath().normalize()));
        assertEquals(quarantineRoot.resolve("java/io"), inspectionCopy.getParent());
        assertTrue(TIMESTAMPED_STREAMS_DOCUMENT
                .matcher(Objects.requireNonNull(inspectionCopy.getFileName(), "inspection copy filename")
                        .toString())
                .matches());
        assertArrayEquals(canonicalDocumentBytes, Files.readAllBytes(inspectionCopy));
    }

    @Test
    void copiesOutsideDocumentationRootWithoutEscapingSiblingQuarantine(@TempDir Path temporaryDirectory)
            throws IOException {
        Path documentationRoot = temporaryDirectory.resolve("data/docs");
        Path outsideDocument = temporaryDirectory.resolve("external/mirror/landing.html");
        byte[] outsideDocumentBytes = "external landing page".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(Objects.requireNonNull(outsideDocument.getParent(), "outside document parent"));
        Files.write(outsideDocument, outsideDocumentBytes);

        IngestionQuarantineService quarantineService = new IngestionQuarantineService(documentationRoot);
        IngestionQuarantineService.QuarantineResult quarantineResult = quarantineService.quarantine(outsideDocument);

        Path quarantineRoot = Objects.requireNonNull(documentationRoot.getParent(), "documentation root parent")
                .resolve(QUARANTINE_DIRECTORY_NAME)
                .toAbsolutePath()
                .normalize();
        Path inspectionCopy = quarantineResult.quarantined();
        assertEquals(outsideDocument.toAbsolutePath().normalize(), quarantineResult.original());
        assertTrue(Files.exists(outsideDocument));
        assertArrayEquals(outsideDocumentBytes, Files.readAllBytes(outsideDocument));
        assertTrue(inspectionCopy.startsWith(quarantineRoot));
        assertFalse(inspectionCopy.startsWith(documentationRoot.toAbsolutePath().normalize()));
        assertEquals(quarantineRoot, inspectionCopy.getParent());
        assertTrue(TIMESTAMPED_LANDING_DOCUMENT
                .matcher(Objects.requireNonNull(inspectionCopy.getFileName(), "inspection copy filename")
                        .toString())
                .matches());
        assertArrayEquals(outsideDocumentBytes, Files.readAllBytes(inspectionCopy));
    }
}
