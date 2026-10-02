package com.williamcallahan.javachat.domain.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Verifies durable ingestion backlog lifecycle transitions. */
class IngestionBacklogStatusTest {

    @Test
    void resumeRestartsTheMarkerBackedInventoryAfterFailure() {
        IngestionBacklogStatus partialBacklog = IngestionBacklogStatus.running("java-21", 4)
                .startBatch(3)
                .completeBatch(1, 1, 1)
                .finish();

        IngestionBacklogStatus resumedBacklog = partialBacklog.resume();

        assertEquals(IngestionBacklogStatus.Lifecycle.RUNNING, resumedBacklog.lifecycle());
        assertEquals(4, resumedBacklog.eligibleFiles());
        assertEquals(0, resumedBacklog.inspectedFiles());
        assertEquals(0, resumedBacklog.processedFiles());
        assertEquals(0, resumedBacklog.skippedFiles());
        assertEquals(0, resumedBacklog.failedFiles());
        assertEquals(4, resumedBacklog.pendingFiles());
        assertEquals(0, resumedBacklog.inProgressFiles());
    }
}
