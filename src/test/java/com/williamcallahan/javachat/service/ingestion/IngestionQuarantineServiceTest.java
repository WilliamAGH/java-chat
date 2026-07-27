package com.williamcallahan.javachat.service.ingestion;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.service.ContentHasher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** Verifies rejected documents are copied safely without mutating their canonical source mirror. */
class IngestionQuarantineServiceTest {
    private static final String CLI_PROFILE = "cli";
    private static final String MALFORMED_UTF_8_DOCUMENT_HEX = "c328ff00fe";
    private static final String MALFORMED_UTF_8_DOCUMENT_SHA_256 =
            "2a7a4b5f058fd584512e1d10961a1dec97666d702a61a33c9472231d366f4ed0";
    private static final String QUARANTINE_DIRECTORY_NAME = ".quarantine";
    private static final Pattern CONTENT_ADDRESSED_STREAMS_DOCUMENT = Pattern.compile("streams\\.[0-9a-f]{64}\\.html");
    private static final Pattern CONTENT_ADDRESSED_LANDING_DOCUMENT = Pattern.compile("landing\\.[0-9a-f]{64}\\.html");

    @Test
    void springCliProfileConstructsServiceWithContentHasher() {
        try (AnnotationConfigApplicationContext applicationContext = new AnnotationConfigApplicationContext()) {
            applicationContext.getEnvironment().setActiveProfiles(CLI_PROFILE);
            applicationContext.register(ContentHasher.class, IngestionQuarantineService.class);
            applicationContext.refresh();

            assertNotNull(applicationContext.getBean(IngestionQuarantineService.class));
        }
    }

    @Test
    void copiesDocumentInsideDocumentationRootToSiblingQuarantineWithRelativePath(@TempDir Path temporaryDirectory)
            throws IOException {
        Path documentationRoot = temporaryDirectory.resolve("data/docs");
        Path canonicalDocument = documentationRoot.resolve("java/io/streams.html");
        byte[] canonicalDocumentBytes = "canonical streams page".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(Objects.requireNonNull(canonicalDocument.getParent(), "canonical document parent"));
        Files.write(canonicalDocument, canonicalDocumentBytes);

        IngestionQuarantineService quarantineService =
                new IngestionQuarantineService(documentationRoot, new ContentHasher());
        IngestionQuarantineService.QuarantineResult quarantineCopy = quarantineService.quarantine(canonicalDocument);

        Path quarantineRoot = Objects.requireNonNull(documentationRoot.getParent(), "documentation root parent")
                .resolve(QUARANTINE_DIRECTORY_NAME)
                .toAbsolutePath()
                .normalize();
        Path inspectionCopy = quarantineCopy.quarantined();
        assertEquals(canonicalDocument.toAbsolutePath().normalize(), quarantineCopy.original());
        assertTrue(Files.exists(canonicalDocument));
        assertArrayEquals(canonicalDocumentBytes, Files.readAllBytes(canonicalDocument));
        assertTrue(inspectionCopy.startsWith(quarantineRoot));
        assertFalse(inspectionCopy.startsWith(documentationRoot.toAbsolutePath().normalize()));
        assertEquals(quarantineRoot.resolve("java/io"), inspectionCopy.getParent());
        assertTrue(CONTENT_ADDRESSED_STREAMS_DOCUMENT
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

        IngestionQuarantineService quarantineService =
                new IngestionQuarantineService(documentationRoot, new ContentHasher());
        IngestionQuarantineService.QuarantineResult quarantineCopy = quarantineService.quarantine(outsideDocument);

        Path quarantineRoot = Objects.requireNonNull(documentationRoot.getParent(), "documentation root parent")
                .resolve(QUARANTINE_DIRECTORY_NAME)
                .toAbsolutePath()
                .normalize();
        Path inspectionCopy = quarantineCopy.quarantined();
        assertEquals(outsideDocument.toAbsolutePath().normalize(), quarantineCopy.original());
        assertTrue(Files.exists(outsideDocument));
        assertArrayEquals(outsideDocumentBytes, Files.readAllBytes(outsideDocument));
        assertTrue(inspectionCopy.startsWith(quarantineRoot));
        assertFalse(inspectionCopy.startsWith(documentationRoot.toAbsolutePath().normalize()));
        assertEquals(quarantineRoot, inspectionCopy.getParent());
        assertTrue(CONTENT_ADDRESSED_LANDING_DOCUMENT
                .matcher(Objects.requireNonNull(inspectionCopy.getFileName(), "inspection copy filename")
                        .toString())
                .matches());
        assertArrayEquals(outsideDocumentBytes, Files.readAllBytes(inspectionCopy));
    }

    @Test
    void reusesVerifiedContentAddressedCopyAcrossRepeatedQuarantineRuns(@TempDir Path temporaryDirectory)
            throws IOException {
        Path documentationRoot = temporaryDirectory.resolve("data/docs");
        Path canonicalDocument = documentationRoot.resolve("java/io/streams.html");
        byte[] canonicalDocumentBytes = "canonical streams page".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(Objects.requireNonNull(canonicalDocument.getParent(), "canonical document parent"));
        Files.write(canonicalDocument, canonicalDocumentBytes);
        IngestionQuarantineService quarantineService =
                new IngestionQuarantineService(documentationRoot, new ContentHasher());

        IngestionQuarantineService.QuarantineResult firstQuarantineCopy =
                quarantineService.quarantine(canonicalDocument);
        IngestionQuarantineService.QuarantineResult repeatedQuarantineCopy =
                quarantineService.quarantine(canonicalDocument);

        assertEquals(firstQuarantineCopy.quarantined(), repeatedQuarantineCopy.quarantined());
        assertArrayEquals(canonicalDocumentBytes, Files.readAllBytes(repeatedQuarantineCopy.quarantined()));
        try (var inspectionCopies = Files.list(
                Objects.requireNonNull(firstQuarantineCopy.quarantined().getParent(), "quarantine copy parent"))) {
            assertEquals(1, inspectionCopies.count());
        }
    }

    @Test
    void derivesReusableInspectionPathFromMalformedUtf8Bytes(@TempDir Path temporaryDirectory) throws IOException {
        Path documentationRoot = temporaryDirectory.resolve("data/docs");
        Path canonicalDocument = documentationRoot.resolve("java/io/streams.html");
        byte[] malformedUtf8DocumentBytes = HexFormat.of().parseHex(MALFORMED_UTF_8_DOCUMENT_HEX);
        Files.createDirectories(Objects.requireNonNull(canonicalDocument.getParent(), "canonical document parent"));
        Files.write(canonicalDocument, malformedUtf8DocumentBytes);
        IngestionQuarantineService quarantineService =
                new IngestionQuarantineService(documentationRoot, new ContentHasher());

        IngestionQuarantineService.QuarantineResult firstQuarantineCopy =
                quarantineService.quarantine(canonicalDocument);
        IngestionQuarantineService.QuarantineResult repeatedQuarantineCopy =
                quarantineService.quarantine(canonicalDocument);

        Path quarantineRoot = Objects.requireNonNull(documentationRoot.getParent(), "documentation root parent")
                .resolve(QUARANTINE_DIRECTORY_NAME)
                .toAbsolutePath()
                .normalize();
        Path expectedInspectionCopy =
                quarantineRoot.resolve("java/io/streams." + MALFORMED_UTF_8_DOCUMENT_SHA_256 + ".html");
        assertEquals(expectedInspectionCopy, firstQuarantineCopy.quarantined());
        assertEquals(expectedInspectionCopy, repeatedQuarantineCopy.quarantined());
        assertArrayEquals(malformedUtf8DocumentBytes, Files.readAllBytes(canonicalDocument));
        assertArrayEquals(malformedUtf8DocumentBytes, Files.readAllBytes(expectedInspectionCopy));
        try (Stream<Path> inspectionCopies =
                Files.list(Objects.requireNonNull(expectedInspectionCopy.getParent(), "quarantine copy parent"))) {
            assertEquals(1, inspectionCopies.count());
        }
    }

    @Test
    void changedContentCreatesDistinctCopyWithoutOverwritingEarlierEvidence(@TempDir Path temporaryDirectory)
            throws IOException {
        Path documentationRoot = temporaryDirectory.resolve("data/docs");
        Path canonicalDocument = documentationRoot.resolve("java/io/streams.html");
        byte[] originalDocumentBytes = "original rejected page".getBytes(StandardCharsets.UTF_8);
        byte[] changedDocumentBytes = "changed rejected page".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(Objects.requireNonNull(canonicalDocument.getParent(), "canonical document parent"));
        Files.write(canonicalDocument, originalDocumentBytes);
        IngestionQuarantineService quarantineService =
                new IngestionQuarantineService(documentationRoot, new ContentHasher());

        IngestionQuarantineService.QuarantineResult originalQuarantineCopy =
                quarantineService.quarantine(canonicalDocument);
        Files.write(canonicalDocument, changedDocumentBytes);
        IngestionQuarantineService.QuarantineResult changedQuarantineCopy =
                quarantineService.quarantine(canonicalDocument);

        assertNotEquals(originalQuarantineCopy.quarantined(), changedQuarantineCopy.quarantined());
        assertArrayEquals(originalDocumentBytes, Files.readAllBytes(originalQuarantineCopy.quarantined()));
        assertArrayEquals(changedDocumentBytes, Files.readAllBytes(changedQuarantineCopy.quarantined()));
    }

    @Test
    void hashesCopiedBytesBeforePublishingEvenWhenCanonicalDocumentChanges(@TempDir Path temporaryDirectory)
            throws IOException {
        Path documentationRoot = temporaryDirectory.resolve("data/docs");
        Path canonicalDocument = documentationRoot.resolve("java/io/streams.html");
        byte[] copiedDocumentBytes = "original rejected page".getBytes(StandardCharsets.UTF_8);
        byte[] changedCanonicalBytes = "changed during quarantine".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(Objects.requireNonNull(canonicalDocument.getParent(), "canonical document parent"));
        Files.write(canonicalDocument, copiedDocumentBytes);
        ContentHasher contentHasher = mock(ContentHasher.class);
        ContentHasher exactByteHasher = new ContentHasher();
        List<Path> hashedPaths = new ArrayList<>();
        when(contentHasher.sha256(any(Path.class))).thenAnswer(hashInvocation -> {
            Path hashedPath = hashInvocation.getArgument(0, Path.class);
            hashedPaths.add(hashedPath);
            if (hashedPaths.size() == 1) {
                Files.write(canonicalDocument, changedCanonicalBytes);
            }
            return exactByteHasher.sha256(hashedPath);
        });
        IngestionQuarantineService quarantineService = new IngestionQuarantineService(documentationRoot, contentHasher);

        IngestionQuarantineService.QuarantineResult quarantineCopy = quarantineService.quarantine(canonicalDocument);

        assertNotEquals(canonicalDocument.toAbsolutePath().normalize(), hashedPaths.getFirst());
        assertFalse(Files.exists(hashedPaths.getFirst()));
        assertEquals(quarantineCopy.quarantined(), hashedPaths.getLast());
        assertArrayEquals(copiedDocumentBytes, Files.readAllBytes(quarantineCopy.quarantined()));
        assertArrayEquals(changedCanonicalBytes, Files.readAllBytes(canonicalDocument));
        assertTrue(Objects.requireNonNull(quarantineCopy.quarantined().getFileName(), "inspection copy filename")
                .toString()
                .contains(exactByteHasher.sha256(quarantineCopy.quarantined())));
    }

    @Test
    void rejectsCorruptedExistingContentAddressWithoutOverwritingEvidence(@TempDir Path temporaryDirectory)
            throws IOException {
        Path documentationRoot = temporaryDirectory.resolve("data/docs");
        Path canonicalDocument = documentationRoot.resolve("java/io/streams.html");
        byte[] canonicalDocumentBytes = "canonical streams page".getBytes(StandardCharsets.UTF_8);
        byte[] corruptedInspectionBytes = "corrupted inspection evidence".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(Objects.requireNonNull(canonicalDocument.getParent(), "canonical document parent"));
        Files.write(canonicalDocument, canonicalDocumentBytes);
        IngestionQuarantineService quarantineService =
                new IngestionQuarantineService(documentationRoot, new ContentHasher());
        IngestionQuarantineService.QuarantineResult firstQuarantineCopy =
                quarantineService.quarantine(canonicalDocument);
        Files.write(firstQuarantineCopy.quarantined(), corruptedInspectionBytes);

        IOException quarantineFailure =
                assertThrows(IOException.class, () -> quarantineService.quarantine(canonicalDocument));

        assertTrue(quarantineFailure.getMessage().contains("fingerprint collision or corrupted inspection copy"));
        assertArrayEquals(corruptedInspectionBytes, Files.readAllBytes(firstQuarantineCopy.quarantined()));
        try (var inspectionCopies = Files.list(
                Objects.requireNonNull(firstQuarantineCopy.quarantined().getParent(), "quarantine copy parent"))) {
            assertEquals(1, inspectionCopies.count());
        }
    }
}
