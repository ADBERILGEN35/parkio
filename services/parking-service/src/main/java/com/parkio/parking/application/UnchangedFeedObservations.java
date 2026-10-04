package com.parkio.parking.application;

import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository.PreviousObservation;
import com.parkio.parking.externalsource.MunicipalTimestampProvenance;
import com.parkio.parking.externalsource.NormalizedMunicipalOccupancy;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Observation time for feeds without source timestamps (CL-F22). İZUM and İSPARK readings carry
 * only the fetch time, so a frozen upstream cache would look LIVE on every fetch. When a feed
 * returns exactly the records of the previous run (the same external ids with the same raw hashes),
 * nothing new has been observed: each reading keeps the time its content was first seen. The
 * source's own aging and stale thresholds then apply to that time through {@code source_age_seconds},
 * so no new threshold is introduced. Any changed, added or missing record means the feed is moving,
 * and the readings keep the fetch time as before.
 */
final class UnchangedFeedObservations {
    private UnchangedFeedObservations() {}

    /** The readings to store, and the time the feed has been unchanged since (null when it changed). */
    record Result(Map<String, NormalizedMunicipalOccupancy> occupancy, Instant unchangedSince) {
        boolean unchanged() {
            return unchangedSince != null;
        }
    }

    static Result apply(
            Map<String, NormalizedMunicipalOccupancy> current, Map<String, PreviousObservation> previous) {
        if (current.isEmpty() || !current.keySet().equals(previous.keySet())) {
            return new Result(current, null);
        }
        Instant since = null;
        for (var entry : current.entrySet()) {
            NormalizedMunicipalOccupancy reading = entry.getValue();
            PreviousObservation before = previous.get(entry.getKey());
            if (reading.timestampProvenance() != MunicipalTimestampProvenance.FETCH
                    || reading.sourceObservedAt() != null
                    || !Objects.equals(reading.rawRecordHash(), before.rawRecordHash())) {
                return new Result(current, null);
            }
            since = since == null || before.observedAt().isBefore(since) ? before.observedAt() : since;
        }
        Map<String, NormalizedMunicipalOccupancy> carried = new LinkedHashMap<>();
        current.forEach((externalId, reading) -> carried.put(externalId, new NormalizedMunicipalOccupancy(
                reading.externalId(),
                previous.get(externalId).observedAt(),
                reading.fetchedAt(),
                reading.timestampProvenance(),
                reading.capacityTotal(),
                reading.occupiedSpaces(),
                reading.availableSpaces(),
                reading.occupancyStatus(),
                reading.rawRecordHash())));
        return new Result(carried, since);
    }
}
