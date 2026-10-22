package com.williamcallahan.javachat.domain.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Verifies durable ingestion backlog lifecycle transitions. */
class IngestionBacklogStatusTest {

    @Test
    void resumeRestartsTheMarkerBackedInventoryAfterFailure() {
        IngestionBacklogStatus partialBacklog = IngestionBacklogStatus.running("java-21", 4)
                .startBatch(3)
                .completeBatch(1, 1, 0, 1)
                .finish();

        IngestionBacklogStatus resumedBacklog = partialBacklog.resume();

        assertEquals(IngestionBacklogStatus.Lifecycle.RUNNING, resumedBacklog.lifecycle());
        assertEquals(4, resumedBacklog.eligibleFiles());
        assertEquals(0, resumedBacklog.inspectedFiles());
        assertEquals(0, resumedBacklog.processedFiles());
        assertEquals(0, resumedBacklog.skippedFiles());
        assertEquals(0, resumedBacklog.excludedFiles());
        assertEquals(0, resumedBacklog.failedFiles());
        assertEquals(4, resumedBacklog.pendingFiles());
        assertEquals(0, resumedBacklog.inProgressFiles());
    }

    @Test
    void completeBatchAccumulatesExcludedFilesSeparatelyFromSkippedDuplicates() {
        IngestionBacklogStatus completeBacklog = IngestionBacklogStatus.running("java-21", 3)
                .startBatch(3)
                .completeBatch(1, 1, 1, 0)
                .finish();

        assertEquals(IngestionBacklogStatus.Lifecycle.COMPLETE, completeBacklog.lifecycle());
        assertEquals(1, completeBacklog.processedFiles());
        assertEquals(1, completeBacklog.skippedFiles());
        assertEquals(1, completeBacklog.excludedFiles());
        assertEquals(0, completeBacklog.failedFiles());
        assertEquals(0, completeBacklog.pendingFiles());
        assertEquals(3, completeBacklog.inspectedFiles());
    }

    @Test
    void abandonRetainsExcludedFilesInTerminalSuccessPrefixAfterInterruption() {
        IngestionBacklogStatus interruptedBacklog =
                IngestionBacklogStatus.running("java-21", 3).startBatch(2).completeBatch(1, 0, 1, 0);

        IngestionBacklogStatus abandonedBacklog = interruptedBacklog.abandon();

        assertEquals(IngestionBacklogStatus.Lifecycle.PARTIAL, abandonedBacklog.lifecycle());
        assertEquals(1, abandonedBacklog.processedFiles());
        assertEquals(0, abandonedBacklog.skippedFiles());
        assertEquals(1, abandonedBacklog.excludedFiles());
        assertEquals(0, abandonedBacklog.failedFiles());
        assertEquals(2, abandonedBacklog.inspectedFiles());
        assertEquals(1, abandonedBacklog.pendingFiles());
    }
}
