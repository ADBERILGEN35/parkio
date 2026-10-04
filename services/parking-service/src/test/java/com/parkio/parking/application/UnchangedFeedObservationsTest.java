package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository.PreviousObservation;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalTimestampProvenance;
import com.parkio.parking.externalsource.NormalizedMunicipalOccupancy;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** CL-F22 (c): a feed that repeats the previous run keeps the time its content was first seen. */
class UnchangedFeedObservationsTest {
    private static final Instant FIRST_SEEN = Instant.parse("2026-10-04T08:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-04T08:30:00Z");

    @Test
    void anUnchangedFeedKeepsTheFirstSeenTime() {
        var result = UnchangedFeedObservations.apply(
                readings(reading("A", "hash-a"), reading("B", "hash-b")),
                Map.of("A", new PreviousObservation("hash-a", FIRST_SEEN),
                        "B", new PreviousObservation("hash-b", FIRST_SEEN.plusSeconds(120))));

        assertThat(result.unchanged()).isTrue();
        assertThat(result.unchangedSince()).isEqualTo(FIRST_SEEN);
        assertThat(result.occupancy().get("A").sourceObservedAt()).isEqualTo(FIRST_SEEN);
        assertThat(result.occupancy().get("B").sourceObservedAt()).isEqualTo(FIRST_SEEN.plusSeconds(120));
        // Everything else is the reading as fetched.
        assertThat(result.occupancy().get("A").fetchedAt()).isEqualTo(NOW);
        assertThat(result.occupancy().get("A").timestampProvenance()).isEqualTo(MunicipalTimestampProvenance.FETCH);
        assertThat(result.occupancy().get("A").availableSpaces()).isEqualTo(7);
        assertThat(result.occupancy().get("A").rawRecordHash()).isEqualTo("hash-a");
    }

    @Test
    void oneChangedRecordMeansTheFeedIsMoving() {
        var current = readings(reading("A", "hash-a"), reading("B", "hash-b-new"));
        var result = UnchangedFeedObservations.apply(current,
                Map.of("A", new PreviousObservation("hash-a", FIRST_SEEN),
                        "B", new PreviousObservation("hash-b", FIRST_SEEN)));

        assertThat(result.unchanged()).isFalse();
        assertThat(result.occupancy()).isSameAs(current);
        assertThat(result.occupancy().values()).allSatisfy(r -> assertThat(r.sourceObservedAt()).isNull());
    }

    @Test
    void anAddedOrMissingRecordMeansTheFeedIsMoving() {
        var previous = Map.of("A", new PreviousObservation("hash-a", FIRST_SEEN));
        assertThat(UnchangedFeedObservations.apply(
                readings(reading("A", "hash-a"), reading("B", "hash-b")), previous).unchanged()).isFalse();
        assertThat(UnchangedFeedObservations.apply(
                readings(reading("A", "hash-a")),
                Map.of("A", new PreviousObservation("hash-a", FIRST_SEEN),
                        "B", new PreviousObservation("hash-b", FIRST_SEEN))).unchanged()).isFalse();
    }

    @Test
    void noPreviousRunOrAnEmptyFeedChangesNothing() {
        var current = readings(reading("A", "hash-a"));
        assertThat(UnchangedFeedObservations.apply(current, Map.of()).occupancy()).isSameAs(current);
        assertThat(UnchangedFeedObservations.apply(Map.of(), Map.of()).unchanged()).isFalse();
    }

    @Test
    void readingsWithASourceTimestampAreLeftAlone() {
        var sourceTimed = new NormalizedMunicipalOccupancy("A", NOW.minusSeconds(30), NOW,
                MunicipalTimestampProvenance.SOURCE, 10, 3, 7, MunicipalOccupancyFreshness.LIVE, "hash-a");
        var current = readings(sourceTimed);
        var result = UnchangedFeedObservations.apply(current,
                Map.of("A", new PreviousObservation("hash-a", FIRST_SEEN)));

        assertThat(result.unchanged()).isFalse();
        assertThat(result.occupancy().get("A").sourceObservedAt()).isEqualTo(NOW.minusSeconds(30));
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
