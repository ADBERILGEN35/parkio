package com.parkio.parking.application.port;

import com.parkio.parking.externalsource.NormalizedMunicipalOccupancy;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface MunicipalOccupancySnapshotRepository {
    /**
     * {@code available} is false when ingest stored the reading as UNAVAILABLE (for example an İZUM
     * car park reported closed, or one without a free-space count): it has no occupancy to publish.
     */
    record Snapshot(Integer capacityTotal, Integer occupiedSpaces, Integer availableSpaces,
                    Instant fetchedAt, Long sourceAgeSeconds, boolean valid, boolean available) {
        public Snapshot(Integer capacityTotal, Integer occupiedSpaces, Integer availableSpaces,
                        Instant fetchedAt, Long sourceAgeSeconds, boolean valid) {
            this(capacityTotal, occupiedSpaces, availableSpaces, fetchedAt, sourceAgeSeconds, valid, true);
        }

        /** A reading that can be published: stored as available and carrying a free-space count. */
        public boolean publishable() {
            return available && availableSpaces != null;
        }
    }

    /** A record of the source's latest run: its raw hash and the run's fetch time. */
    record PreviousObservation(String rawRecordHash, Instant fetchedAt) {}

    boolean insertIfAbsent(UUID facilityId, UUID sourceId, UUID sourceLinkId,
                           UUID syncRunId, NormalizedMunicipalOccupancy occupancy);
    Optional<Snapshot> latestForFacility(UUID facilityId);
    Optional<Snapshot> latestForFacilityAndSourceKey(UUID facilityId, String sourceKey);

    /** Latest occupancy observation for a municipal source (by {@code fetched_at}). */
    Optional<Snapshot> latestForSource(UUID sourceId);

    /** The snapshots of the source's latest sync run, by link external id. */
    Map<String, PreviousObservation> latestRunObservations(UUID sourceId);

    long count();

    /**
     * Count rows older than {@code cutoff} that are not the latest snapshot for their
     * {@code (facility_id, source_id)} group (deterministic: {@code fetched_at DESC, id DESC}).
     */
    long countExpiredExcludingLatest(Instant cutoff);

    /**
     * Delete up to {@code batchSize} expired non-latest snapshots. Latest row per
     * facility/source is never deleted. Returns number of rows deleted in this batch.
     */
    int deleteExpiredExcludingLatest(Instant cutoff, int batchSize);
}
