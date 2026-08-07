package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.parkio.parking.application.MunicipalFacilityIngestWriter.FacilityPersistResult;
import com.parkio.parking.application.port.MunicipalDataSourceRepository;
import com.parkio.parking.application.port.MunicipalSourceSyncRunRepository;
import com.parkio.parking.application.port.OsmImportSupportRepository;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalParkingSourceAdapter;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import com.parkio.parking.externalsource.schema.SchemaFingerprint;
import com.parkio.parking.infrastructure.izum.IzumMunicipalParkingAdapter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MunicipalFacilitySyncServiceSetReconciliationTest {
    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");
    private static final UUID SOURCE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RUN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock MunicipalParkingSourceAdapter adapter;
    @Mock MunicipalDataSourceRepository sources;
    @Mock MunicipalSourceSyncRunRepository runs;
    @Mock MunicipalFacilityIngestWriter ingestWriter;
    @Mock OsmImportSupportRepository setReconciliation;

    MunicipalFacilitySyncService service;
    ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(adapter.sourceKey()).thenReturn(IzumMunicipalParkingAdapter.SOURCE_KEY);
        lenient().when(adapter.reconciliationMode()).thenReturn(
                com.parkio.parking.externalsource.provider.ReconciliationMode.AUTHORITATIVE_FULL_SET);
        service = new MunicipalFacilitySyncService(
                List.of(adapter),
                sources,
                runs,
                ingestWriter,
                setReconciliation,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(sources.requireBySourceKey(IzumMunicipalParkingAdapter.SOURCE_KEY))
                .thenReturn(new MunicipalDataSourceRepository.Source(
                        SOURCE_ID,
                        IzumMunicipalParkingAdapter.SOURCE_KEY,
                        "publisher",
                        "attribution",
                        300,
                        900,
                        null,
                        true));
        when(runs.tryStart(eq(SOURCE_ID), any(), eq(NOW))).thenReturn(Optional.of(RUN_ID));
        lenient().when(runs.isRunning(RUN_ID)).thenReturn(true);
        lenient().when(runs.complete(eq(RUN_ID), any(), any(), any(), any())).thenReturn(true);
    }

    @Test
    void successfulShrinkDeactivatesMissingLinks() {
        ArrayNode payload = mapper.createArrayNode();
        payload.add(record("A"));
        payload.add(record("B"));
        stubSuccessfulFetch(payload, List.of(facility("A"), facility("B")), Set.of("A", "B", "C"));
        when(setReconciliation.deactivateMissing(eq(SOURCE_ID), eq(Set.of("A", "B")), eq(NOW))).thenReturn(1);
        when(setReconciliation.activeExternalIds(SOURCE_ID))
                .thenReturn(Set.of("A", "B", "C"))
                .thenReturn(Set.of("A", "B"));

        var result = service.sync(IzumMunicipalParkingAdapter.SOURCE_KEY);

        assertThat(result.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        assertThat(result.recordsAccepted()).isEqualTo(2);
        assertThat(result.recordsDeactivated()).isEqualTo(1);
        assertThat(result.activeLinkCount()).isEqualTo(2);
        verify(setReconciliation).deactivateMissing(SOURCE_ID, Set.of("A", "B"), NOW);
        verify(sources).markSuccessful(SOURCE_ID, NOW);
    }

    @Test
    void upstreamFailureDoesNotDeactivate() {
        when(adapter.fetch()).thenThrow(new IllegalStateException("upstream down"));

        var result = service.sync(IzumMunicipalParkingAdapter.SOURCE_KEY);

        assertThat(result.status()).isEqualTo(MunicipalSyncRunStatus.FAILED);
        assertThat(result.recordsDeactivated()).isZero();
        verify(setReconciliation, never()).deactivateMissing(any(), any(), any());
        verify(sources, never()).markSuccessful(any(), any());
    }

    @Test
    void partialSuccessDoesNotDeactivate() {
        ArrayNode payload = mapper.createArrayNode();
        payload.add(record("A"));
        payload.add(record("B"));
        when(adapter.fetch()).thenReturn(payload);
        when(adapter.validateContract(payload)).thenReturn(SchemaFingerprint.fromArray(payload));
        when(adapter.normalizeFacilities(eq(payload), eq(NOW))).thenReturn(List.of(facility("A")));
        when(adapter.normalizeOccupancy(eq(payload), eq(NOW))).thenReturn(List.of());
        when(setReconciliation.activeExternalIds(SOURCE_ID)).thenReturn(Set.of("A", "B"));
        when(ingestWriter.persistLiveAdapterFacility(eq(SOURCE_ID), eq(RUN_ID), any(), any(), any(), eq(NOW)))
                .thenReturn(new FacilityPersistResult(UUID.randomUUID(), false, true, false));

        var result = service.sync(IzumMunicipalParkingAdapter.SOURCE_KEY);

        assertThat(result.status()).isEqualTo(MunicipalSyncRunStatus.PARTIAL_SUCCESS);
        assertThat(result.recordsDeactivated()).isZero();
        verify(setReconciliation, never()).deactivateMissing(any(), any(), any());
    }

    @Test
    void reactivationIncrementsWhenInactiveLinkReturns() {
        ArrayNode payload = mapper.createArrayNode();
        payload.add(record("A"));
        when(adapter.fetch()).thenReturn(payload);
        when(adapter.validateContract(payload)).thenReturn(SchemaFingerprint.fromArray(payload));
        when(adapter.normalizeFacilities(eq(payload), eq(NOW))).thenReturn(List.of(facility("A")));
        when(adapter.normalizeOccupancy(eq(payload), eq(NOW))).thenReturn(List.of());
        when(setReconciliation.activeExternalIds(SOURCE_ID)).thenReturn(Set.of()).thenReturn(Set.of("A"));
        when(setReconciliation.deactivateMissing(eq(SOURCE_ID), eq(Set.of("A")), eq(NOW))).thenReturn(0);
        when(ingestWriter.persistLiveAdapterFacility(eq(SOURCE_ID), eq(RUN_ID), any(), any(), any(), eq(NOW)))
                .thenReturn(new FacilityPersistResult(UUID.randomUUID(), false, true, true));

        var result = service.sync(IzumMunicipalParkingAdapter.SOURCE_KEY);

        assertThat(result.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        assertThat(result.recordsReactivated()).isEqualTo(1);
        assertThat(result.recordsInserted()).isZero();
    }

    @Test
    void ownershipLostBeforeReconcileSkipsDeactivate() {
        ArrayNode payload = mapper.createArrayNode();
        payload.add(record("A"));
        stubSuccessfulFetch(payload, List.of(facility("A")), Set.of("A", "B"));
        when(runs.isRunning(RUN_ID)).thenReturn(false);

        var result = service.sync(IzumMunicipalParkingAdapter.SOURCE_KEY);

        assertThat(result.status()).isEqualTo(MunicipalSyncRunStatus.FAILED);
        assertThat(result.errorCategory()).isEqualTo("ownership_lost");
        verify(setReconciliation, never()).deactivateMissing(any(), any(), any());
        verify(sources, never()).markSuccessful(any(), any());
    }

    @Test
    void lateCompleteDoesNotMarkSuccessful() {
        ArrayNode payload = mapper.createArrayNode();
        payload.add(record("A"));
        stubSuccessfulFetch(payload, List.of(facility("A")), Set.of("A"));
        when(setReconciliation.deactivateMissing(any(), any(), any())).thenReturn(0);
        when(runs.complete(eq(RUN_ID), any(), any(), any(), any())).thenReturn(false);

        var result = service.sync(IzumMunicipalParkingAdapter.SOURCE_KEY);

        assertThat(result.status()).isEqualTo(MunicipalSyncRunStatus.FAILED);
        assertThat(result.errorCategory()).isEqualTo("ownership_lost");
        verify(sources, never()).markSuccessful(any(), any());
    }

    private void stubSuccessfulFetch(
            ArrayNode payload,
            List<NormalizedMunicipalFacility> facilities,
            Set<String> previouslyActive) {
        when(adapter.fetch()).thenReturn(payload);
        when(adapter.validateContract(payload)).thenReturn(SchemaFingerprint.fromArray(payload));
        when(adapter.normalizeFacilities(eq(payload), eq(NOW))).thenReturn(facilities);
        when(adapter.normalizeOccupancy(eq(payload), eq(NOW))).thenReturn(List.of());
        when(setReconciliation.activeExternalIds(SOURCE_ID)).thenReturn(previouslyActive);
        when(ingestWriter.persistLiveAdapterFacility(eq(SOURCE_ID), eq(RUN_ID), any(), any(), any(), eq(NOW)))
                .thenAnswer(inv -> new FacilityPersistResult(UUID.randomUUID(), true, false, true));
    }

    private ObjectNode record(String ufid) {
        ObjectNode node = mapper.createObjectNode();
        node.put("ufid", ufid);
        node.put("lat", 38.4);
        node.put("lng", 27.1);
        node.set("occupancy", mapper.createObjectNode().set("total",
                mapper.createObjectNode().put("free", 1).put("occupied", 1)));
        return node;
    }

    private static NormalizedMunicipalFacility facility(String externalId) {
        return new NormalizedMunicipalFacility(
                externalId,
                "Operator",
                MunicipalFacilityType.ON_STREET,
                "Name " + externalId,
                null,
                38.4,
                27.1,
                null,
                MunicipalAccessClassification.PUBLIC,
                Map.of(),
                "hash-" + externalId);
    }
}
