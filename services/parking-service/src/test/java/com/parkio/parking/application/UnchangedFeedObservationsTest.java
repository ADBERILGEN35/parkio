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

/** CL-F22 (c): a feed that repeats the previous run keeps the time of the last run in which it changed. */
class UnchangedFeedObservationsTest {
    private static final Instant CHANGED_AT = Instant.parse("2026-10-04T08:00:00Z");
    private static final Instant PREVIOUS_RUN = Instant.parse("2026-10-04T08:28:00Z");
    private static final Instant NOW = Instant.parse("2026-10-04T08:30:00Z");

    @Test
    void anUnchangedFeedKeepsTheTimeOfTheLastRunInWhichItChanged() {
        var result = UnchangedFeedObservations.apply(
                readings(reading("A", "hash-a"), reading("B", "hash-b")),
                Map.of("A", carried("hash-a", CHANGED_AT),
                        "B", carried("hash-b", CHANGED_AT.plusSeconds(120))));

        assertThat(result.unchanged()).isTrue();
        assertThat(result.unchangedSince()).isEqualTo(CHANGED_AT);
        assertThat(result.occupancy().get("A").sourceObservedAt()).isEqualTo(CHANGED_AT);
        assertThat(result.occupancy().get("B").sourceObservedAt()).isEqualTo(CHANGED_AT.plusSeconds(120));
        // The previous run was unchanged too: the streak continues.
        assertThat(result.streakStart()).isFalse();
        // Everything else is the reading as fetched.
        assertThat(result.occupancy().get("A").fetchedAt()).isEqualTo(NOW);
        assertThat(result.occupancy().get("A").timestampProvenance()).isEqualTo(MunicipalTimestampProvenance.FETCH);
        assertThat(result.occupancy().get("A").availableSpaces()).isEqualTo(7);
        assertThat(result.occupancy().get("A").rawRecordHash()).isEqualTo("hash-a");
    }

    @Test
    void theFirstUnchangedRunAfterAChangeStartsAStreak() {
        // The previous run stored the fetch time: it was a changing run.
        var result = UnchangedFeedObservations.apply(
                readings(reading("A", "hash-a"), reading("B", "hash-b")),
                Map.of("A", changed("hash-a"), "B", changed("hash-b")));

        assertThat(result.unchanged()).isTrue();
        assertThat(result.streakStart()).isTrue();
        assertThat(result.unchangedSince()).isEqualTo(PREVIOUS_RUN);
        assertThat(result.occupancy().values())
                .allSatisfy(r -> assertThat(r.sourceObservedAt()).isEqualTo(PREVIOUS_RUN));
    }

    @Test
    void oneChangedRecordMeansTheFeedIsMoving() {
        var current = readings(reading("A", "hash-a"), reading("B", "hash-b-new"));
        var result = UnchangedFeedObservations.apply(current,
                Map.of("A", carried("hash-a", CHANGED_AT), "B", carried("hash-b", CHANGED_AT)));

        assertThat(result.unchanged()).isFalse();
        assertThat(result.streakStart()).isFalse();
        assertThat(result.occupancy()).isSameAs(current);
        assertThat(result.occupancy().values()).allSatisfy(r -> assertThat(r.sourceObservedAt()).isNull());
    }

    @Test
    void anAddedOrMissingRecordMeansTheFeedIsMoving() {
        var previous = Map.of("A", carried("hash-a", CHANGED_AT));
        assertThat(UnchangedFeedObservations.apply(
                readings(reading("A", "hash-a"), reading("B", "hash-b")), previous).unchanged()).isFalse();
        assertThat(UnchangedFeedObservations.apply(
                readings(reading("A", "hash-a")),
                Map.of("A", carried("hash-a", CHANGED_AT), "B", carried("hash-b", CHANGED_AT))).unchanged())
                .isFalse();
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
        var result = UnchangedFeedObservations.apply(current, Map.of("A", carried("hash-a", CHANGED_AT)));

        assertThat(result.unchanged()).isFalse();
        assertThat(result.occupancy().get("A").sourceObservedAt()).isEqualTo(NOW.minusSeconds(30));
    }

    /** A record of a previous run in which the feed changed: observed when it was fetched. */
    private static PreviousObservation changed(String hash) {
        return new PreviousObservation(hash, PREVIOUS_RUN, PREVIOUS_RUN);
    }

    /** A record of a previous run that was itself unchanged: it carries an earlier observation time. */
    private static PreviousObservation carried(String hash, Instant observedAt) {
        return new PreviousObservation(hash, observedAt, PREVIOUS_RUN);
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
