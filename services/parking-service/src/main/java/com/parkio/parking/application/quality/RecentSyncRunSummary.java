package com.parkio.parking.application.quality;

import java.time.Instant;

/**
 * Bounded view of a completed sync run. Deliberately omits the run id, correlation id
 * and payload/schema hashes so the report stays free of raw ingest identifiers.
 */
public record RecentSyncRunSummary(
        String status,
        String errorCategory,
        Instant startedAt,
        Instant completedAt) {}
