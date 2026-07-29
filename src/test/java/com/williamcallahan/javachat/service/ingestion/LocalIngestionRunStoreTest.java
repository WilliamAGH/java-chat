package com.williamcallahan.javachat.service.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.application.ingestion.IngestionAlreadyRunningException;
import com.williamcallahan.javachat.domain.ingestion.IngestionBacklogStatus;
import com.williamcallahan.javachat.service.LocalStoreService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/** Verifies durable local-ingestion progress and cross-process run ownership. */
class LocalIngestionRunStoreTest {
    private static final String INVENTORY_FINGERPRINT = "inventory-one";
    private static final int ABANDONED_LOCK_RE_READ_SEQUENCE = 2;

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
    void doesNotOverwriteCheckpointCompletedBeforeAbandonedLockAcquisition(@TempDir Path temporaryDirectory)
            throws IOException {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AtomicInteger checkpointReadCount = new AtomicInteger();
        AtomicReference<Runnable> ownerCompletion = new AtomicReference<>(() -> {});
        ObjectMapper racingObjectMapper = new OwnerCompletionRacingObjectMapper(checkpointReadCount, ownerCompletion);
        LocalIngestionRunStore runStore = runStore(temporaryDirectory, meterRegistry, racingObjectMapper);
        Path documentationDirectory = temporaryDirectory.resolve("docs").toAbsolutePath();
        IngestionBacklogStatus runningBacklog =
                IngestionBacklogStatus.running("docs", 3).startBatch(3);
        runStore.write(documentationDirectory, runningBacklog, INVENTORY_FINGERPRINT);
        IngestionBacklogStatus completedBacklog =
                runningBacklog.completeBatch(2, 1, 0).finish();
        ownerCompletion.set(() -> {
            try {
                runStore.write(documentationDirectory, completedBacklog, INVENTORY_FINGERPRINT);
            } catch (IOException completionWriteFailure) {
                throw new UncheckedIOException(completionWriteFailure);
            }
        });

        assertEquals(
                0.0,
                meterRegistry.get("javachat.ingestion.backlog.pending").gauge().value());
        IngestionBacklogStatus persistedBacklog =
                runStore.read(documentationDirectory, INVENTORY_FINGERPRINT).orElseThrow();
        assertEquals(IngestionBacklogStatus.Lifecycle.COMPLETE, persistedBacklog.lifecycle());
        assertEquals(completedBacklog, persistedBacklog);
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
        return runStore(temporaryDirectory, meterRegistry, new ObjectMapper());
    }

    private static LocalIngestionRunStore runStore(
            Path temporaryDirectory, SimpleMeterRegistry meterRegistry, ObjectMapper objectMapper) {
        Path generationDirectory = temporaryDirectory.resolve("qwen3-embedding-4b-2560/local");
        LocalStoreService localStoreService = new LocalStoreService(
                generationDirectory.resolve("snapshots").toString(),
                generationDirectory.resolve("parsed").toString(),
                generationDirectory.resolve("index").toString(),
                "local",
                null);
        ReflectionTestUtils.invokeMethod(localStoreService, "createStoreDirectories");
        return new LocalIngestionRunStore(localStoreService, objectMapper, meterRegistry);
    }

    /**
     * Simulates the original run owner persisting a COMPLETE checkpoint between the reconciler's
     * unlocked read and its under-lock re-read.
     */
    private static final class OwnerCompletionRacingObjectMapper extends ObjectMapper {
        private static final long serialVersionUID = 1L;

        private final AtomicInteger checkpointReadCount;
        private final AtomicReference<Runnable> ownerCompletion;

        private OwnerCompletionRacingObjectMapper(
                AtomicInteger checkpointReadCount, AtomicReference<Runnable> ownerCompletion) {
            this.checkpointReadCount = checkpointReadCount;
            this.ownerCompletion = ownerCompletion;
        }

        @Override
        public <T> T readValue(File checkpointFile, Class<T> checkpointType) throws IOException {
            if (checkpointReadCount.incrementAndGet() == ABANDONED_LOCK_RE_READ_SEQUENCE) {
                ownerCompletion.get().run();
            }
            return super.readValue(checkpointFile, checkpointType);
        }
    }
}
