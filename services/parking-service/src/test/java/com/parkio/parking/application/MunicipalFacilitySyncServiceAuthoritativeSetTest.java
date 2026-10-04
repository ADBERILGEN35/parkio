package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.externalsource.MunicipalParkingSourceAdapter;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import com.parkio.parking.externalsource.provider.ReconciliationMode;
import com.parkio.parking.infrastructure.izum.IzumMunicipalParkingAdapter;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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

    // CL-F22 (b): the adapter-based check, which the sync uses, honours the run status.
    @Test
    void failedOrSkippedRunsNeverReconcileEvenWithATrustworthySnapshot() {
        MunicipalParkingSourceAdapter adapter = authoritativeAdapter();
        for (MunicipalSyncRunStatus status : List.of(MunicipalSyncRunStatus.FAILED, MunicipalSyncRunStatus.SKIPPED)) {
            assertThat(MunicipalFacilitySyncService.isAuthoritativeSet(adapter, status, 2, Set.of("a", "b"), 2, 2))
                    .as(status.name())
                    .isFalse();
        }
    }

    @Test
    void successAndIntentionalPartialSuccessStillReconcile() {
        MunicipalParkingSourceAdapter adapter = authoritativeAdapter();
        assertThat(MunicipalFacilitySyncService.isAuthoritativeSet(
                        adapter, MunicipalSyncRunStatus.SUCCESS, 2, Set.of("a", "b"), 2, 2))
                .isTrue();
        // ANPARK-style: every received row is valid; the adapter filtered active=false members.
        assertThat(MunicipalFacilitySyncService.isAuthoritativeSet(
                        adapter, MunicipalSyncRunStatus.PARTIAL_SUCCESS, 1, Set.of("a"), 3, 3))
                .isTrue();
        // Invalid rows: fewer valid ids than received rows, so a partial feed never reconciles.
        assertThat(MunicipalFacilitySyncService.isAuthoritativeSet(
                        adapter, MunicipalSyncRunStatus.PARTIAL_SUCCESS, 2, Set.of("a", "b"), 2, 3))
                .isFalse();
    }

    private static MunicipalParkingSourceAdapter authoritativeAdapter() {
        MunicipalParkingSourceAdapter adapter = Mockito.mock(MunicipalParkingSourceAdapter.class);
        Mockito.when(adapter.reconciliationMode()).thenReturn(ReconciliationMode.AUTHORITATIVE_FULL_SET);
        return adapter;
    }
}
