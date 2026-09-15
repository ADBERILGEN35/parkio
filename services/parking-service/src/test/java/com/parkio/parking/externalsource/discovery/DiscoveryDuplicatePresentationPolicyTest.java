package com.parkio.parking.externalsource.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DiscoveryDuplicatePresentationPolicyTest {
    private final DiscoveryDuplicatePresentationPolicy policy =
            new DiscoveryDuplicatePresentationPolicy(100);

    @Test
    void strongIzumOsmDuplicateSuppressesLoser() {
        UUID izumId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID osmId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var izum = candidate(
                izumId, DiscoveryDuplicatePresentationPolicy.Family.IZUM,
                "Konak Otoparki", "IZELMAN", "Konak Mah.", 38.42000, 27.14000, 120,
                MunicipalFacilityType.OFF_STREET, MunicipalOccupancyFreshness.LIVE, "izum");
        var osm = candidate(
                osmId, DiscoveryDuplicatePresentationPolicy.Family.OSM,
                "Konak Otoparki", "IZELMAN", "Konak Mah.", 38.42005, 27.14005, 120,
                MunicipalFacilityType.OFF_STREET, MunicipalOccupancyFreshness.UNAVAILABLE, "osm");

        assertThat(policy.classify(izum, osm).outcome())
                .isEqualTo(DiscoveryDuplicatePresentationPolicy.Outcome.SUPPRESS);

        var applied = policy.apply(List.of(izum, osm), 10);
        assertThat(applied.kept()).containsExactly("izum");
        assertThat(applied.suppressed()).isEqualTo(1);
        assertThat(applied.strongDuplicates()).isEqualTo(1);
    }

    @Test
    void distanceOnlyNeverSuppresses() {
        var a = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000011"),
                DiscoveryDuplicatePresentationPolicy.Family.IZUM,
                "Alpha Lot", null, null, 38.42000, 27.14000, null,
                MunicipalFacilityType.UNKNOWN, MunicipalOccupancyFreshness.LIVE, "a");
        var b = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000012"),
                DiscoveryDuplicatePresentationPolicy.Family.OSM,
                "Completely Different", null, null, 38.42005, 27.14005, null,
                MunicipalFacilityType.UNKNOWN, MunicipalOccupancyFreshness.UNAVAILABLE, "b");

        assertThat(policy.classify(a, b).outcome())
                .isEqualTo(DiscoveryDuplicatePresentationPolicy.Outcome.DISTANCE_ONLY);
        assertThat(policy.apply(List.of(a, b), 10).kept()).containsExactly("a", "b");
    }

    @Test
    void nameOnlyNeverSuppresses() {
        var a = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000021"),
                DiscoveryDuplicatePresentationPolicy.Family.IZUM,
                "Shared Name Garage", "Op A", "Konak", 38.42000, 27.14000, 100,
                MunicipalFacilityType.OFF_STREET, MunicipalOccupancyFreshness.LIVE, "a");
        var b = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000022"),
                DiscoveryDuplicatePresentationPolicy.Family.OSM,
                "Shared Name Garage", "Op B", "Buca", 38.50000, 27.20000, 100,
                MunicipalFacilityType.OFF_STREET, MunicipalOccupancyFreshness.UNAVAILABLE, "b");

        assertThat(policy.classify(a, b).outcome())
                .isIn(
                        DiscoveryDuplicatePresentationPolicy.Outcome.NAME_ONLY,
                        DiscoveryDuplicatePresentationPolicy.Outcome.OUTSIDE_RADIUS,
                        DiscoveryDuplicatePresentationPolicy.Outcome.HARD_CONFLICT);
        assertThat(policy.apply(List.of(a, b), 10).kept()).containsExactly("a", "b");
    }

    @Test
    void incompatibleTypesAreHardConflict() {
        var a = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000031"),
                DiscoveryDuplicatePresentationPolicy.Family.IZUM,
                "Konak Otoparki", "IZELMAN", "Konak", 38.42000, 27.14000, 100,
                MunicipalFacilityType.OFF_STREET, MunicipalOccupancyFreshness.LIVE, "a");
        var b = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000032"),
                DiscoveryDuplicatePresentationPolicy.Family.OSM,
                "Konak Otoparki", "IZELMAN", "Konak", 38.42005, 27.14005, 100,
                MunicipalFacilityType.ON_STREET, MunicipalOccupancyFreshness.UNAVAILABLE, "b");

        assertThat(policy.classify(a, b).outcome())
                .isEqualTo(DiscoveryDuplicatePresentationPolicy.Outcome.HARD_CONFLICT);
        assertThat(policy.apply(List.of(a, b), 10).kept()).containsExactly("a", "b");
    }

    @Test
    void differentDistrictsAreBothShown() {
        var a = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000041"),
                DiscoveryDuplicatePresentationPolicy.Family.IZUM,
                "Merkez Otopark", "IZELMAN", "Bornova Merkez", 38.46000, 27.22000, 80,
                MunicipalFacilityType.OFF_STREET, MunicipalOccupancyFreshness.LIVE, "a");
        var b = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000042"),
                DiscoveryDuplicatePresentationPolicy.Family.OSM,
                "Merkez Otopark", "IZELMAN", "Karsiyaka Merkez", 38.46010, 27.22010, 80,
                MunicipalFacilityType.OFF_STREET, MunicipalOccupancyFreshness.UNAVAILABLE, "b");

        assertThat(policy.classify(a, b).outcome())
                .isEqualTo(DiscoveryDuplicatePresentationPolicy.Outcome.HARD_CONFLICT);
        assertThat(policy.apply(List.of(a, b), 10).kept()).containsExactly("a", "b");
    }

    @Test
    void capacityHardConflictKeepsBothInComplex() {
        var a = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000051"),
                DiscoveryDuplicatePresentationPolicy.Family.IZUM,
                "Forum Garage A", "Operator A", "Bayrakli Forum", 38.45500, 27.17000, 400,
                MunicipalFacilityType.OFF_STREET, MunicipalOccupancyFreshness.LIVE, "a");
        var b = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000052"),
                DiscoveryDuplicatePresentationPolicy.Family.OSM,
                "Forum Garage B", "Operator B", "Bayrakli Forum", 38.45505, 27.17005, 40,
                MunicipalFacilityType.OFF_STREET, MunicipalOccupancyFreshness.UNAVAILABLE, "b");

        assertThat(policy.classify(a, b).outcome())
                .isIn(
                        DiscoveryDuplicatePresentationPolicy.Outcome.HARD_CONFLICT,
                        DiscoveryDuplicatePresentationPolicy.Outcome.INSUFFICIENT_SIGNALS,
                        DiscoveryDuplicatePresentationPolicy.Outcome.BELOW_THRESHOLD);
        assertThat(policy.apply(List.of(a, b), 10).kept()).containsExactly("a", "b");
    }

    @Test
    void winnerPrefersLiveIzumOverOsm() {
        var izumStale = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000061"),
                DiscoveryDuplicatePresentationPolicy.Family.IZUM,
                "Lot", "Op", "Addr", 38.42, 27.14, 100,
                MunicipalFacilityType.OFF_STREET, MunicipalOccupancyFreshness.STALE, "stale");
        var osm = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000062"),
                DiscoveryDuplicatePresentationPolicy.Family.OSM,
                "Lot", "Op", "Addr", 38.42005, 27.14005, 100,
                MunicipalFacilityType.OFF_STREET, MunicipalOccupancyFreshness.UNAVAILABLE, "osm");
        var izumLive = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000063"),
                DiscoveryDuplicatePresentationPolicy.Family.IZUM,
                "Lot", "Op", "Addr", 38.42002, 27.14002, 100,
                MunicipalFacilityType.OFF_STREET, MunicipalOccupancyFreshness.LIVE, "live");

        assertThat(DiscoveryDuplicatePresentationPolicy.selectWinner(izumStale, osm).payload())
                .isEqualTo("stale");
        assertThat(DiscoveryDuplicatePresentationPolicy.selectWinner(izumLive, osm).payload())
                .isEqualTo("live");
        assertThat(DiscoveryDuplicatePresentationPolicy.selectWinner(izumLive, izumStale).payload())
                .isEqualTo("live");
    }

    @Test
    void osmOsmAndIzelmanPairsAreUnsupported() {
        var osmA = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000071"),
                DiscoveryDuplicatePresentationPolicy.Family.OSM,
                "Lot", "Op", "Addr", 38.42, 27.14, 100,
                MunicipalFacilityType.OFF_STREET, MunicipalOccupancyFreshness.UNAVAILABLE, "a");
        var osmB = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000072"),
                DiscoveryDuplicatePresentationPolicy.Family.OSM,
                "Lot", "Op", "Addr", 38.42005, 27.14005, 100,
                MunicipalFacilityType.OFF_STREET, MunicipalOccupancyFreshness.UNAVAILABLE, "b");
        var other = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000073"),
                DiscoveryDuplicatePresentationPolicy.Family.OTHER,
                "Lot", "Op", "Addr", 38.42005, 27.14005, 100,
                MunicipalFacilityType.OFF_STREET, MunicipalOccupancyFreshness.UNAVAILABLE, "c");

        assertThat(policy.classify(osmA, osmB).outcome())
                .isEqualTo(DiscoveryDuplicatePresentationPolicy.Outcome.SKIP_UNSUPPORTED_PAIR);
        assertThat(policy.classify(osmA, other).outcome())
                .isEqualTo(DiscoveryDuplicatePresentationPolicy.Outcome.SKIP_UNSUPPORTED_PAIR);
    }

    @Test
    void boundedFetchLimitRespectsHardMaximum() {
        assertThat(DiscoveryDuplicatePresentationPolicy.boundedFetchLimit(50, 2, 200)).isEqualTo(100);
        assertThat(DiscoveryDuplicatePresentationPolicy.boundedFetchLimit(80, 2, 100)).isEqualTo(100);
        assertThat(DiscoveryDuplicatePresentationPolicy.boundedFetchLimit(10, 2, 200)).isEqualTo(20);
    }

    @Test
    void suppressionHappensBeforeFinalLimitAndRefills() {
        UUID izum = UUID.fromString("00000000-0000-0000-0000-000000000081");
        UUID osmDup = UUID.fromString("00000000-0000-0000-0000-000000000082");
        UUID third = UUID.fromString("00000000-0000-0000-0000-000000000083");
        var a = candidate(
                izum, DiscoveryDuplicatePresentationPolicy.Family.IZUM,
                "Konak Otoparki", "IZELMAN", "Konak", 38.42000, 27.14000, 100,
                MunicipalFacilityType.OFF_STREET, MunicipalOccupancyFreshness.LIVE, "izum");
        var b = candidate(
                osmDup, DiscoveryDuplicatePresentationPolicy.Family.OSM,
                "Konak Otoparki", "IZELMAN", "Konak", 38.42005, 27.14005, 100,
                MunicipalFacilityType.OFF_STREET, MunicipalOccupancyFreshness.UNAVAILABLE, "osm");
        var c = candidate(
                third, DiscoveryDuplicatePresentationPolicy.Family.OSM,
                "Unrelated Lot", "Other Op", "Buca", 38.42100, 27.14100, 50,
                MunicipalFacilityType.OFF_STREET, MunicipalOccupancyFreshness.UNAVAILABLE, "third");

        var applied = policy.apply(List.of(a, b, c), 2);
        assertThat(applied.kept()).containsExactly("izum", "third");
        assertThat(applied.suppressed()).isEqualTo(1);
    }

    private static DiscoveryDuplicatePresentationPolicy.Candidate candidate(
            UUID id,
            DiscoveryDuplicatePresentationPolicy.Family family,
            String name,
            String operator,
            String address,
            double lat,
            double lng,
            Integer capacity,
            MunicipalFacilityType type,
            MunicipalOccupancyFreshness freshness,
            String payload) {
        return new DiscoveryDuplicatePresentationPolicy.Candidate(
                id,
                family,
                name,
                operator,
                address,
                lat,
                lng,
                capacity,
                type,
                MunicipalAccessClassification.PUBLIC,
                freshness,
                payload);
    }
}
