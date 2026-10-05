package com.parkio.parking.application;

import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository.PreviousObservation;
import com.parkio.parking.externalsource.MunicipalFeedChange;
import com.parkio.parking.externalsource.MunicipalTimestampProvenance;
import com.parkio.parking.externalsource.NormalizedMunicipalOccupancy;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

/**
 * Detects a feed without source timestamps that repeats its previous run (CL-F22, owner option C).
 * İZUM and İSPARK readings carry only the fetch time, so a frozen upstream cache would look LIVE on
 * every fetch. A feed counts as unchanged when it returns exactly the previous run's records: the same
 * external ids with the same raw hashes. This is operator visibility only; the readings are stored with
 * their fetch time as before.
 */
final class UnchangedFeed {
    private UnchangedFeed() {}

    /**
     * Compares this run's readings with the previous run's. Returns null when no comparison applies:
     * no readings, no previous run, or a reading that has its own source timestamp.
     */
    static MunicipalFeedChange compare(
            Map<String, NormalizedMunicipalOccupancy> current,
            Map<String, PreviousObservation> previous,
            Instant fetchedAt) {
        if (current.isEmpty() || previous.isEmpty()) {
            return null;
        }
        for (NormalizedMunicipalOccupancy reading : current.values()) {
            if (reading.timestampProvenance() != MunicipalTimestampProvenance.FETCH
                    || reading.sourceObservedAt() != null) {
                return null;
            }
        }
        Instant previousRunFetchedAt = previous.values().stream()
                .map(PreviousObservation::fetchedAt)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        boolean unchanged = current.keySet().equals(previous.keySet())
                && current.entrySet().stream().allMatch(entry -> Objects.equals(
                        entry.getValue().rawRecordHash(), previous.get(entry.getKey()).rawRecordHash()));
        return new MunicipalFeedChange(unchanged, previousRunFetchedAt, fetchedAt);
    }
}
