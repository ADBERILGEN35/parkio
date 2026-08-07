package com.parkio.parking.externalsource.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.parkio.parking.application.MunicipalFacilityIngestWriter;
import com.parkio.parking.application.MunicipalFacilityIngestWriter.FacilityPersistResult;
import com.parkio.parking.application.MunicipalFacilitySyncService;
import com.parkio.parking.application.port.MunicipalDataSourceRepository;
import com.parkio.parking.application.port.MunicipalSourceSyncRunRepository;
import com.parkio.parking.application.port.OsmImportSupportRepository;
import com.parkio.parking.externalsource.MunicipalParkingSourceAdapter;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import com.parkio.parking.externalsource.schema.SchemaFingerprint;
import com.parkio.parking.infrastructure.fake.FakeTestMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.izum.IzumMunicipalParkingAdapter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProviderIsolationTest {
    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");
    private static final UUID FAKE_SOURCE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID IZUM_SOURCE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RUN = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void fakeFailureDoesNotDeactivateIzumLinks() {
        FakeTestMunicipalParkingAdapter fake = new FakeTestMunicipalParkingAdapter(new ObjectMapper()) {
            @Override
            public com.fasterxml.jackson.databind.JsonNode fetch() {
                throw new IllegalStateException("fake down");
            }
        };
        MunicipalParkingSourceAdapter izum = mock(MunicipalParkingSourceAdapter.class);
        when(izum.sourceKey()).thenReturn(IzumMunicipalParkingAdapter.SOURCE_KEY);
        when(izum.reconciliationMode()).thenReturn(ReconciliationMode.AUTHORITATIVE_FULL_SET);

        MunicipalDataSourceRepository sources = mock(MunicipalDataSourceRepository.class);
        MunicipalSourceSyncRunRepository runs = mock(MunicipalSourceSyncRunRepository.class);
        MunicipalFacilityIngestWriter ingest = mock(MunicipalFacilityIngestWriter.class);
        OsmImportSupportRepository reconciliation = mock(OsmImportSupportRepository.class);

        when(sources.requireBySourceKey(FakeTestMunicipalParkingAdapter.SOURCE_KEY))
                .thenReturn(source(FAKE_SOURCE, FakeTestMunicipalParkingAdapter.SOURCE_KEY));
        when(runs.tryStart(eq(FAKE_SOURCE), any(), eq(NOW))).thenReturn(Optional.of(RUN));

        MunicipalFacilitySyncService service = new MunicipalFacilitySyncService(
                List.of(fake, izum), sources, runs, ingest, reconciliation,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var result = service.sync(FakeTestMunicipalParkingAdapter.SOURCE_KEY);

        assertThat(result.status()).isEqualTo(MunicipalSyncRunStatus.FAILED);
        verify(reconciliation, never()).deactivateMissing(eq(IZUM_SOURCE), any(), any());
        verify(reconciliation, never()).deactivateMissing(eq(FAKE_SOURCE), any(), any());
        verify(ingest, never()).persistLiveAdapterFacility(any(), any(), any(), any(), any(), any());
    }

    @Test
    void fakeAuthoritativeReconcileIsSourceScopedOnly() {
        ObjectMapper mapper = new ObjectMapper();
        FakeTestMunicipalParkingAdapter fake = new FakeTestMunicipalParkingAdapter(mapper);
        ArrayNode payload = mapper.createArrayNode();
        ObjectNode row = payload.addObject();
        row.put("externalId", "SAME-ID");
        row.put("name", "Fake");
        row.put("lat", 38.4);
        row.put("lng", 27.1);
        fake.setPayload(payload);

        MunicipalDataSourceRepository sources = mock(MunicipalDataSourceRepository.class);
        MunicipalSourceSyncRunRepository runs = mock(MunicipalSourceSyncRunRepository.class);
        MunicipalFacilityIngestWriter ingest = mock(MunicipalFacilityIngestWriter.class);
        OsmImportSupportRepository reconciliation = mock(OsmImportSupportRepository.class);

        when(sources.requireBySourceKey(FakeTestMunicipalParkingAdapter.SOURCE_KEY))
                .thenReturn(source(FAKE_SOURCE, FakeTestMunicipalParkingAdapter.SOURCE_KEY));
        when(runs.tryStart(eq(FAKE_SOURCE), any(), eq(NOW))).thenReturn(Optional.of(RUN));
        when(reconciliation.activeExternalIds(FAKE_SOURCE)).thenReturn(Set.of("SAME-ID", "GONE"));
        when(reconciliation.deactivateMissing(eq(FAKE_SOURCE), eq(Set.of("SAME-ID")), eq(NOW))).thenReturn(1);
        when(ingest.persistLiveAdapterFacility(
                        eq(FAKE_SOURCE), eq(RUN), eq(FakeTestMunicipalParkingAdapter.SOURCE_KEY),
                        any(), any(), eq(NOW)))
                .thenReturn(new FacilityPersistResult(UUID.randomUUID(), false, true, false));

        MunicipalFacilitySyncService service = new MunicipalFacilitySyncService(
                List.of(fake), sources, runs, ingest, reconciliation,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var result = service.sync(FakeTestMunicipalParkingAdapter.SOURCE_KEY);

        assertThat(result.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        assertThat(result.recordsDeactivated()).isEqualTo(1);
        verify(reconciliation).deactivateMissing(FAKE_SOURCE, Set.of("SAME-ID"), NOW);
        verify(reconciliation, never()).deactivateMissing(eq(IZUM_SOURCE), any(), any());
    }

    private static MunicipalDataSourceRepository.Source source(UUID id, String key) {
        return new MunicipalDataSourceRepository.Source(id, key, "p", "a", 300, 900, null, true);
    }
}
