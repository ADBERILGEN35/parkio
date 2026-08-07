package com.parkio.parking.externalsource;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.externalsource.izelman.IzelmanSourceKeys;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MunicipalSourceOccupancyAuthorityPolicyTest {
    private static final Instant T0 = Instant.parse("2026-08-03T12:00:00Z");
    private final MunicipalSourceOccupancyAuthorityPolicy policy =
            new MunicipalSourceOccupancyAuthorityPolicy();

    @Test
    void onlyLiveOccupancyCapableSourcesMayContributeOccupancy() {
        assertThat(policy.mayContributeOccupancy(MunicipalSourceIdentity.IZUM)).isTrue();
        assertThat(policy.mayContributeOccupancy(MunicipalSourceIdentity.FAKE_TEST)).isTrue();
        assertThat(policy.mayContributeOccupancy(MunicipalSourceIdentity.OSM)).isFalse();
        assertThat(policy.mayContributeOccupancy(IzelmanSourceKeys.OPEN)).isFalse();
        assertThat(policy.mayContributeOccupancy(IzelmanSourceKeys.ROADSIDE)).isFalse();
        assertThat(policy.mayContributeOccupancy(IzelmanSourceKeys.TARIFFS)).isFalse();
    }

    @Test
    void osmSuccessfulImportDoesNotImplyLive() {
        assertThat(policy.classify(
                        MunicipalSourceIdentity.OSM,
                        T0.minusSeconds(30),
                        0L,
                        true,
                        300,
                        900,
                        T0))
                .isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
    }

    @Test
    void osmPublicationAndSchedulerDoNotDetermineFreshness() {
        assertThat(policy.classify(
                        MunicipalSourceIdentity.OSM, null, null, true, 300, 900, T0))
                .isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
    }

    @Test
    void osmMissingObservationIsUnavailable() {
        assertThat(policy.classify(
                        MunicipalSourceIdentity.OSM, null, null, true, 300, 900, T0))
                .isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
    }

    @Test
    void izelmanAlwaysUnavailableEvenWithObservation() {
        assertThat(policy.classify(
                        IzelmanSourceKeys.OPEN,
                        T0.minusSeconds(10),
                        0L,
                        true,
                        300,
                        900,
                        T0))
                .isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
    }

    @Test
    void izumFreshObservationIsLive() {
        assertThat(policy.classify(
                        MunicipalSourceIdentity.IZUM,
                        T0.minusSeconds(30),
                        0L,
                        true,
                        300,
                        900,
                        T0))
                .isEqualTo(MunicipalOccupancyFreshness.LIVE);
    }

    @Test
    void izumAgingObservationIsAging() {
        assertThat(policy.classify(
                        MunicipalSourceIdentity.IZUM,
                        T0.minusSeconds(400),
                        0L,
                        true,
                        300,
                        900,
                        T0))
                .isEqualTo(MunicipalOccupancyFreshness.AGING);
    }

    @Test
    void izumStaleObservationIsStale() {
        assertThat(policy.classify(
                        MunicipalSourceIdentity.IZUM,
                        T0.minusSeconds(1000),
                        0L,
                        true,
                        300,
                        900,
                        T0))
                .isEqualTo(MunicipalOccupancyFreshness.STALE);
    }

    @Test
    void izumMissingObservationIsUnavailable() {
        assertThat(policy.classify(
                        MunicipalSourceIdentity.IZUM, null, null, true, 300, 900, T0))
                .isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
    }

    @Test
    void invalidIzumObservationIsInvalid() {
        assertThat(policy.classify(
                        MunicipalSourceIdentity.IZUM,
                        T0.minusSeconds(10),
                        0L,
                        false,
                        300,
                        900,
                        T0))
                .isEqualTo(MunicipalOccupancyFreshness.INVALID);
    }

    @Test
    void sourceModeDoesNotOverwriteFreshness() {
        // OPERATOR_IMPORTED OSM with a fresh timestamp still UNAVAILABLE.
        assertThat(policy.classify(
                        MunicipalSourceIdentity.OSM,
                        T0.minusSeconds(5),
                        0L,
                        true,
                        300,
                        900,
                        T0))
                .isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
        // SCHEDULED İZUM with no observation is UNAVAILABLE (not LIVE from mode).
        assertThat(policy.classify(
                        MunicipalSourceIdentity.IZUM, null, null, true, 300, 900, T0))
                .isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
    }
}
