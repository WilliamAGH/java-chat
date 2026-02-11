package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies hash marker metadata round-trip encoding and metadata drift detection
 * in {@link LocalStoreService}.
 */
class LocalStoreServiceTest {

    private static final String SAMPLE_HASH = "abc123def456";
    private static final String SAMPLE_TITLE = "java.util.Optional";
    private static final String SAMPLE_PACKAGE = "java.util";
    private static final String DIFFERENT_TITLE = "java.util.List";
    private static final String DIFFERENT_PACKAGE = "java.util.stream";

    @TempDir
    Path tempDir;

    private LocalStoreService localStoreService;

    @BeforeEach
    void setUp() {
        String snapshotDir = tempDir.resolve("snapshots").toString();
        String parsedDir = tempDir.resolve("parsed").toString();
        String indexDir = tempDir.resolve("index").toString();
        ProgressTracker progressTracker = new ProgressTracker(parsedDir, indexDir);
        localStoreService = new LocalStoreService(snapshotDir, parsedDir, indexDir, progressTracker);
        localStoreService.createStoreDirectories();
    }

    @Test
    void markHashIngested_roundTripsBase64MetadataCorrectly() throws IOException {
        localStoreService.markHashIngested(SAMPLE_HASH, SAMPLE_TITLE, SAMPLE_PACKAGE);

        assertTrue(localStoreService.isHashIngested(SAMPLE_HASH));
        assertFalse(
                localStoreService.hasHashMetadataChanged(SAMPLE_HASH, SAMPLE_TITLE, SAMPLE_PACKAGE),
                "Metadata should match after round-trip");
    }

    @Test
    void hasHashMetadataChanged_detectsTitleDrift() throws IOException {
        localStoreService.markHashIngested(SAMPLE_HASH, SAMPLE_TITLE, SAMPLE_PACKAGE);

        assertTrue(
                localStoreService.hasHashMetadataChanged(SAMPLE_HASH, DIFFERENT_TITLE, SAMPLE_PACKAGE),
                "Changed title should be detected as metadata drift");
    }

    @Test
    void hasHashMetadataChanged_detectsPackageDrift() throws IOException {
        localStoreService.markHashIngested(SAMPLE_HASH, SAMPLE_TITLE, SAMPLE_PACKAGE);

        assertTrue(
                localStoreService.hasHashMetadataChanged(SAMPLE_HASH, SAMPLE_TITLE, DIFFERENT_PACKAGE),
                "Changed package should be detected as metadata drift");
    }

    @Test
    void hasHashMetadataChanged_returnsFalseWhenMarkerDoesNotExist() {
        assertFalse(
                localStoreService.hasHashMetadataChanged("nonexistent_hash", SAMPLE_TITLE, SAMPLE_PACKAGE),
                "Non-existent marker should report no change");
    }

    @Test
    void markHashIngested_updatesMarkerWhenMetadataChanges() throws IOException {
        localStoreService.markHashIngested(SAMPLE_HASH, SAMPLE_TITLE, SAMPLE_PACKAGE);
        localStoreService.markHashIngested(SAMPLE_HASH, DIFFERENT_TITLE, DIFFERENT_PACKAGE);

        assertFalse(
                localStoreService.hasHashMetadataChanged(SAMPLE_HASH, DIFFERENT_TITLE, DIFFERENT_PACKAGE),
                "Metadata should match updated values after overwrite");
        assertTrue(
                localStoreService.hasHashMetadataChanged(SAMPLE_HASH, SAMPLE_TITLE, SAMPLE_PACKAGE),
                "Original metadata should now be detected as drift");
    }

    @Test
    void hasHashMetadataChanged_throwsOnCorruptedBase64Marker() throws IOException {
        Path markerPath = tempDir.resolve("index").resolve(SAMPLE_HASH);
        String corruptedPayload = "1\ntitleB64=!!!not-valid-base64!!!\npackageB64=alsoBroken\n";
        Files.writeString(markerPath, corruptedPayload, StandardCharsets.UTF_8);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> localStoreService.hasHashMetadataChanged(SAMPLE_HASH, SAMPLE_TITLE, SAMPLE_PACKAGE));
        assertEquals("Failed to read hash ingestion marker for hash: " + SAMPLE_HASH, thrown.getMessage());
    }

    @Test
    void markHashIngested_handlesNullAndEmptyMetadata() throws IOException {
        localStoreService.markHashIngested(SAMPLE_HASH, null, null);

        assertTrue(localStoreService.isHashIngested(SAMPLE_HASH));
        assertFalse(
                localStoreService.hasHashMetadataChanged(SAMPLE_HASH, "", ""),
                "Null metadata normalizes to empty string and should match empty query");
    }

    @Test
    void markHashIngested_noArgOverloadWritesEmptyMetadata() throws IOException {
        localStoreService.markHashIngested(SAMPLE_HASH);

        assertTrue(localStoreService.isHashIngested(SAMPLE_HASH));
        assertFalse(
                localStoreService.hasHashMetadataChanged(SAMPLE_HASH, "", ""),
                "No-arg overload should write empty metadata");
    }

    @Test
    void markHashIngested_roundTripsUnicodeMetadata() throws IOException {
        String unicodeTitle = "クラス概要 — java.util.Optional<T>";
        String unicodePackage = "日本語パッケージ";

        localStoreService.markHashIngested(SAMPLE_HASH, unicodeTitle, unicodePackage);

        assertFalse(
                localStoreService.hasHashMetadataChanged(SAMPLE_HASH, unicodeTitle, unicodePackage),
                "Unicode metadata should survive Base64 round-trip");
        assertTrue(
                localStoreService.hasHashMetadataChanged(SAMPLE_HASH, "ASCII title", unicodePackage),
                "Changed title should be detected even when package contains Unicode");
    }
}
