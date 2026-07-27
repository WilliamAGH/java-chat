package com.williamcallahan.javachat.service.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.application.ingestion.IngestionAlreadyRunningException;
import com.williamcallahan.javachat.domain.ingestion.IngestionBacklogStatus;
import com.williamcallahan.javachat.service.LocalStoreService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/** Verifies durable local-ingestion progress and cross-process run ownership. */
class LocalIngestionRunStoreTest {
    private static final String INVENTORY_FINGERPRINT = "inventory-one";

    @Test
    void persistsPartialBacklogAndPublishesCounts(@TempDir Path temporaryDirectory) throws IOException {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        LocalIngestionRunStore runStore = runStore(temporaryDirectory, meterRegistry);
        Path documentationDirectory = temporaryDirectory.resolve("docs").toAbsolutePath();
        IngestionBacklogStatus partialBacklog = IngestionBacklogStatus.running(documentationDirectory.toString(), 4)
                .startBatch(3)
                .completeBatch(1, 1, 1)
                .finish();

        runStore.write(documentationDirectory, partialBacklog, INVENTORY_FINGERPRINT);

        assertEquals(
                partialBacklog,
                runStore.read(documentationDirectory, INVENTORY_FINGERPRINT).orElseThrow());
        assertEquals(
                1.0,
                meterRegistry.get("javachat.ingestion.backlog.pending").gauge().value());
        assertEquals(
                1.0,
                meterRegistry.get("javachat.ingestion.backlog.failed").gauge().value());
    }

    @Test
    void invalidatesCheckpointWhenInventoryIdentityChanges(@TempDir Path temporaryDirectory) throws IOException {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        LocalIngestionRunStore runStore = runStore(temporaryDirectory, meterRegistry);
        Path documentationDirectory = temporaryDirectory.resolve("docs").toAbsolutePath();
        IngestionBacklogStatus partialBacklog = IngestionBacklogStatus.running(documentationDirectory.toString(), 2)
                .startBatch(1)
                .completeBatch(1, 0, 0)
                .finish();

        runStore.write(documentationDirectory, partialBacklog, INVENTORY_FINGERPRINT);

        assertEquals(
                0,
                runStore.read(documentationDirectory, "inventory-two").stream().count());
        assertEquals(
                0.0,
                meterRegistry.get("javachat.ingestion.backlog.pending").gauge().value());
    }

    @Test
    void rejectsSecondClaimWhileDirectoryIsOwned(@TempDir Path temporaryDirectory) throws IOException {
        LocalIngestionRunStore runStore = runStore(temporaryDirectory, new SimpleMeterRegistry());
        Path documentationDirectory = temporaryDirectory.resolve("docs").toAbsolutePath();

        LocalIngestionRunStore.RunClaim runClaim = runStore.claim(documentationDirectory);
        try (runClaim) {
            assertThrows(IngestionAlreadyRunningException.class, () -> runStore.claim(documentationDirectory));
        }

        LocalIngestionRunStore.RunClaim nextRunClaim = runStore.claim(documentationDirectory);
        try (nextRunClaim) {
            assertEquals(
                    0,
                    runStore.read(documentationDirectory, INVENTORY_FINGERPRINT).stream()
                            .count());
        }
    }

    @Test
    void reconcilesAbandonedRunningCheckpointBeforePublishingGauges(@TempDir Path temporaryDirectory)
            throws IOException {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        LocalIngestionRunStore runStore = runStore(temporaryDirectory, meterRegistry);
        Path documentationDirectory = temporaryDirectory.resolve("docs").toAbsolutePath();
        IngestionBacklogStatus runningBacklog =
                IngestionBacklogStatus.running("docs", 3).startBatch(2);

        try (LocalIngestionRunStore.RunClaim ignoredClaim = runStore.claim(documentationDirectory)) {
            runStore.write(documentationDirectory, runningBacklog, INVENTORY_FINGERPRINT);
            assertEquals(
                    2.0,
                    meterRegistry
                            .get("javachat.ingestion.backlog.in.progress")
                            .gauge()
                            .value());
        }

        assertEquals(
                0.0,
                meterRegistry
                        .get("javachat.ingestion.backlog.in.progress")
                        .gauge()
                        .value());
        assertEquals(
                3.0,
                meterRegistry.get("javachat.ingestion.backlog.pending").gauge().value());
        IngestionBacklogStatus reconciledBacklog =
                runStore.read(documentationDirectory, INVENTORY_FINGERPRINT).orElseThrow();
        assertEquals(IngestionBacklogStatus.Lifecycle.PARTIAL, reconciledBacklog.lifecycle());
        assertEquals(0, reconciledBacklog.inProgressFiles());
        assertEquals(3, reconciledBacklog.pendingFiles());
    }

    @Test
    void distinctDirectoriesWithPreviouslyCollidingSafeNamesHaveIndependentClaims(@TempDir Path temporaryDirectory)
            throws IOException {
        LocalIngestionRunStore runStore = runStore(temporaryDirectory, new SimpleMeterRegistry());
        Path nestedDirectory = temporaryDirectory.resolve("docs/a/b");
        Path underscoredDirectory = temporaryDirectory.resolve("docs/a_b");
        LocalIngestionRunStore.RunClaim nestedClaim = runStore.claim(nestedDirectory);
        LocalIngestionRunStore.RunClaim underscoredClaim = runStore.claim(underscoredDirectory);

        try (nestedClaim;
                underscoredClaim) {
            assertEquals(
                    0,
                    runStore.read(nestedDirectory, INVENTORY_FINGERPRINT).stream()
                            .count());
            assertEquals(
                    0,
                    runStore.read(underscoredDirectory, INVENTORY_FINGERPRINT).stream()
                            .count());
        }
    }

    private static LocalIngestionRunStore runStore(Path temporaryDirectory, SimpleMeterRegistry meterRegistry) {
        Path generationDirectory = temporaryDirectory.resolve("qwen3-embedding-4b-2560/local");
        LocalStoreService localStoreService = new LocalStoreService(
                generationDirectory.resolve("snapshots").toString(),
                generationDirectory.resolve("parsed").toString(),
                generationDirectory.resolve("index").toString(),
                "local",
                null);
        ReflectionTestUtils.invokeMethod(localStoreService, "createStoreDirectories");
        return new LocalIngestionRunStore(localStoreService, new ObjectMapper(), meterRegistry);
    }
}
