package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import com.parkio.parking.infrastructure.izum.IzumMunicipalParkingAdapter;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MunicipalFacilitySyncServiceAuthoritativeSetTest {
    @Test
    void izumSuccessWithNonEmptyMatchingSetIsAuthoritative() {
        assertThat(MunicipalFacilitySyncService.isAuthoritativeSet(
                        IzumMunicipalParkingAdapter.SOURCE_KEY,
                        MunicipalSyncRunStatus.SUCCESS,
                        2,
                        Set.of("a", "b")))
                .isTrue();
    }

    @Test
    void partialSuccessIsNotAuthoritative() {
        assertThat(MunicipalFacilitySyncService.isAuthoritativeSet(
                        IzumMunicipalParkingAdapter.SOURCE_KEY,
                        MunicipalSyncRunStatus.PARTIAL_SUCCESS,
                        2,
                        Set.of("a", "b")))
                .isFalse();
    }

    @Test
    void emptyOrFailedSetsAreNotAuthoritative() {
        assertThat(MunicipalFacilitySyncService.isAuthoritativeSet(
                        IzumMunicipalParkingAdapter.SOURCE_KEY,
                        MunicipalSyncRunStatus.SUCCESS,
                        0,
                        Set.of()))
                .isFalse();
        assertThat(MunicipalFacilitySyncService.isAuthoritativeSet(
                        IzumMunicipalParkingAdapter.SOURCE_KEY,
                        MunicipalSyncRunStatus.FAILED,
                        2,
                        Set.of("a", "b")))
                .isFalse();
    }

    @Test
    void nonAuthoritativeSourcesAreNotAuthoritativeForThisPath() {
        assertThat(MunicipalFacilitySyncService.isAuthoritativeSet(
                        "osm-geofabrik-turkey",
                        MunicipalSyncRunStatus.SUCCESS,
                        2,
                        Set.of("a", "b")))
                .isFalse();
    }

    @Test
    void fakeTestProviderIsAuthoritativeWhenSuccessful() {
        assertThat(MunicipalFacilitySyncService.isAuthoritativeSet(
                        "parkio-fake-test-provider",
                        MunicipalSyncRunStatus.SUCCESS,
                        2,
                        Set.of("a", "b")))
                .isTrue();
    }
}
