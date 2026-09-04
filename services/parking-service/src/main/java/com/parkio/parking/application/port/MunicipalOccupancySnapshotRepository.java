package com.parkio.parking.application.port;

import com.parkio.parking.externalsource.NormalizedMunicipalOccupancy;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface MunicipalOccupancySnapshotRepository {
    record Snapshot(Integer capacityTotal, Integer occupiedSpaces, Integer availableSpaces,
                    Instant fetchedAt, Long sourceAgeSeconds, boolean valid) {}
    boolean insertIfAbsent(UUID facilityId, UUID sourceId, UUID sourceLinkId,
                           UUID syncRunId, NormalizedMunicipalOccupancy occupancy);
    Optional<Snapshot> latestForFacility(UUID facilityId);
    Optional<Snapshot> latestForFacilityAndSourceKey(UUID facilityId, String sourceKey);

    /** Latest occupancy observation for a municipal source (by {@code fetched_at}). */
    Optional<Snapshot> latestForSource(UUID sourceId);

    long count();
}
