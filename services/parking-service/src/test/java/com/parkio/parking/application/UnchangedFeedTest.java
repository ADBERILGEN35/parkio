package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository.PreviousObservation;
import com.parkio.parking.externalsource.MunicipalFeedChange;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalTimestampProvenance;
import com.parkio.parking.externalsource.NormalizedMunicipalOccupancy;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** CL-F22, owner option C: detecting a feed that repeats its previous run, for operators only. */
class UnchangedFeedTest {
    private static final Instant PREVIOUS_RUN = Instant.parse("2026-10-05T08:28:00Z");
    private static final Instant NOW = Instant.parse("2026-10-05T08:30:00Z");

    @Test
    void theSameRecordsWithTheSameHashesAreUnchanged() {
        var change = UnchangedFeed.compare(
                readings(reading("A", "hash-a"), reading("B", "hash-b")),
                Map.of("A", previous("hash-a"), "B", previous("hash-b")), NOW);

        assertThat(change).isEqualTo(new MunicipalFeedChange(true, PREVIOUS_RUN, NOW));
    }

    @Test
    void oneChangedRecordMeansTheFeedMoved() {
        var change = UnchangedFeed.compare(
                readings(reading("A", "hash-a"), reading("B", "hash-b-new")),
                Map.of("A", previous("hash-a"), "B", previous("hash-b")), NOW);

        assertThat(change).isEqualTo(new MunicipalFeedChange(false, PREVIOUS_RUN, NOW));
    }

    @Test
    void anAddedOrMissingRecordMeansTheFeedMoved() {
        assertThat(UnchangedFeed.compare(
                readings(reading("A", "hash-a"), reading("B", "hash-b")),
                Map.of("A", previous("hash-a")), NOW).unchanged()).isFalse();
        assertThat(UnchangedFeed.compare(
                readings(reading("A", "hash-a")),
                Map.of("A", previous("hash-a"), "B", previous("hash-b")), NOW).unchanged()).isFalse();
    }

    @Test
    void noPreviousRunOrNoReadingsGiveNoComparison() {
        assertThat(UnchangedFeed.compare(readings(reading("A", "hash-a")), Map.of(), NOW)).isNull();
        assertThat(UnchangedFeed.compare(Map.of(), Map.of("A", previous("hash-a")), NOW)).isNull();
    }

    @Test
    void readingsWithASourceTimestampGiveNoComparison() {
        var sourceTimed = new NormalizedMunicipalOccupancy("A", NOW.minusSeconds(30), NOW,
                MunicipalTimestampProvenance.SOURCE, 10, 3, 7, MunicipalOccupancyFreshness.LIVE, "hash-a");

        assertThat(UnchangedFeed.compare(readings(sourceTimed), Map.of("A", previous("hash-a")), NOW)).isNull();
    }

    private static PreviousObservation previous(String hash) {
        return new PreviousObservation(hash, PREVIOUS_RUN);
    }

    private static NormalizedMunicipalOccupancy reading(String id, String hash) {
        return new NormalizedMunicipalOccupancy(id, null, NOW, MunicipalTimestampProvenance.FETCH,
                10, 3, 7, MunicipalOccupancyFreshness.LIVE, hash);
    }

    private static Map<String, NormalizedMunicipalOccupancy> readings(NormalizedMunicipalOccupancy... values) {
        Map<String, NormalizedMunicipalOccupancy> map = new LinkedHashMap<>();
        for (NormalizedMunicipalOccupancy value : values) {
            map.put(value.externalId(), value);
        }
        return map;
    }
}
