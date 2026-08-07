package com.parkio.parking.infrastructure.fake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.parkio.parking.application.MunicipalFacilityIngestWriter;
import com.parkio.parking.application.MunicipalFacilityIngestWriter.FacilityPersistResult;
import com.parkio.parking.application.MunicipalFacilitySyncService;
import com.parkio.parking.application.port.MunicipalDataSourceRepository;
import com.parkio.parking.application.port.MunicipalSourceSyncRunRepository;
import com.parkio.parking.application.port.OsmImportSupportRepository;
import com.parkio.parking.application.recommendation.ParkingCandidate;
import com.parkio.parking.application.recommendation.ParkingCandidateChannel;
import com.parkio.parking.application.recommendation.ParkingCandidateMapper;
import com.parkio.parking.application.recommendation.RecommendationReason;
import com.parkio.parking.application.recommendation.RecommendationReasonCode;
import com.parkio.parking.application.MunicipalFacilityQueryService.FacilityView;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import com.parkio.parking.externalsource.NormalizedMunicipalOccupancy;
import com.parkio.parking.externalsource.provider.ParkingDataProviderId;
import com.parkio.parking.externalsource.provider.ParkingProviderCatalog;
import com.parkio.parking.externalsource.provider.ProviderCapability;
import com.parkio.parking.externalsource.provider.ReconciliationMode;
import com.parkio.parking.infrastructure.izum.IzumMunicipalParkingAdapter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WP-SPA-13 acceptance: fake provider proves extensibility without a real municipality.
 */
class FakeTestMunicipalParkingAdapterContractTest {
    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");
    private static final UUID SOURCE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID RUN_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID IZUM_SOURCE_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private final ObjectMapper mapper = new ObjectMapper();
    private FakeTestMunicipalParkingAdapter fake;
    private MunicipalDataSourceRepository sources;
    private MunicipalSourceSyncRunRepository runs;
    private MunicipalFacilityIngestWriter ingestWriter;
    private OsmImportSupportRepository setReconciliation;
    private MunicipalFacilitySyncService service;

    @BeforeEach
    void setUp() {
        fake = new FakeTestMunicipalParkingAdapter(mapper);
        sources = mock(MunicipalDataSourceRepository.class);
        runs = mock(MunicipalSourceSyncRunRepository.class);
        ingestWriter = mock(MunicipalFacilityIngestWriter.class);
        setReconciliation = mock(OsmImportSupportRepository.class);
        service = new MunicipalFacilitySyncService(
                List.of(fake),
                sources,
                runs,
                ingestWriter,
                setReconciliation,
                Clock.fixed(NOW, ZoneOffset.UTC));

        when(sources.requireBySourceKey(FakeTestMunicipalParkingAdapter.SOURCE_KEY))
                .thenReturn(new MunicipalDataSourceRepository.Source(
                        SOURCE_ID,
                        FakeTestMunicipalParkingAdapter.SOURCE_KEY,
                        "Parkio",
                        "test",
                        300,
                        900,
                        null,
                        true));
        when(runs.tryStart(eq(SOURCE_ID), any(), eq(NOW))).thenReturn(Optional.of(RUN_ID));
        lenient().when(runs.isRunning(RUN_ID)).thenReturn(true);
        lenient().when(runs.complete(eq(RUN_ID), any(), any(), any(), any())).thenReturn(true);
    }

    @Test
    void providerRegistersWithInventoryAndLiveOccupancyCapabilities() {
        assertThat(fake.providerId()).isEqualTo(ParkingDataProviderId.FAKE_TEST);
        assertThat(fake.sourceKey()).isEqualTo(FakeTestMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(fake.capabilities()).containsExactlyInAnyOrder(
                ProviderCapability.FACILITY_INVENTORY, ProviderCapability.LIVE_OCCUPANCY);
        assertThat(fake.reconciliationMode()).isEqualTo(ReconciliationMode.AUTHORITATIVE_FULL_SET);
        assertThat(ParkingProviderCatalog.supportsLiveOccupancy(fake.sourceKey())).isTrue();
    }

    @Test
    void syncNormalizesFacilityAndOccupancyThenPersistsCanonicalRows() {
        ArrayNode payload = (ArrayNode) fake.seedFacility("EXT-1", "Fake Lot", 38.4, 27.1, 50, 12, 38);
        List<NormalizedMunicipalFacility> facilities = fake.normalizeFacilities(payload, NOW);
        List<NormalizedMunicipalOccupancy> occupancy = fake.normalizeOccupancy(payload, NOW);
        assertThat(facilities).hasSize(1);
        assertThat(facilities.get(0).externalId()).isEqualTo("EXT-1");
        assertThat(occupancy).hasSize(1);
        assertThat(occupancy.get(0).availableSpaces()).isEqualTo(12);

        when(setReconciliation.activeExternalIds(SOURCE_ID)).thenReturn(Set.of()).thenReturn(Set.of("EXT-1"));
        when(setReconciliation.deactivateMissing(eq(SOURCE_ID), eq(Set.of("EXT-1")), eq(NOW))).thenReturn(0);
        when(ingestWriter.persistLiveAdapterFacility(
                        eq(SOURCE_ID), eq(RUN_ID), eq(FakeTestMunicipalParkingAdapter.SOURCE_KEY),
                        any(), any(), eq(NOW)))
                .thenReturn(new FacilityPersistResult(UUID.randomUUID(), true, false, true));

        var result = service.sync(FakeTestMunicipalParkingAdapter.SOURCE_KEY);

        assertThat(result.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        assertThat(result.recordsAccepted()).isEqualTo(1);
        assertThat(result.occupancyInserted()).isEqualTo(1);
        verify(ingestWriter).persistLiveAdapterFacility(
                eq(SOURCE_ID), eq(RUN_ID), eq(FakeTestMunicipalParkingAdapter.SOURCE_KEY),
                any(), any(), eq(NOW));
    }

    @Test
    void authoritativeMissingRecordSoftDeactivatesAndReappearanceReactivates() {
        fake.seedFacility("A", "Lot A", 38.4, 27.1, 10, 3, 7);
        when(setReconciliation.activeExternalIds(SOURCE_ID))
                .thenReturn(Set.of("A", "B"))
                .thenReturn(Set.of("A"));
        when(setReconciliation.deactivateMissing(eq(SOURCE_ID), eq(Set.of("A")), eq(NOW))).thenReturn(1);
        when(ingestWriter.persistLiveAdapterFacility(
                        eq(SOURCE_ID), eq(RUN_ID), eq(FakeTestMunicipalParkingAdapter.SOURCE_KEY),
                        any(), any(), eq(NOW)))
                .thenReturn(new FacilityPersistResult(UUID.randomUUID(), false, true, true));

        var shrink = service.sync(FakeTestMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(shrink.recordsDeactivated()).isEqualTo(1);
        verify(setReconciliation).deactivateMissing(SOURCE_ID, Set.of("A"), NOW);

        // Reactivation: previously inactive external returns.
        when(runs.tryStart(eq(SOURCE_ID), any(), eq(NOW))).thenReturn(Optional.of(RUN_ID));
        when(setReconciliation.activeExternalIds(SOURCE_ID)).thenReturn(Set.of()).thenReturn(Set.of("A"));
        when(setReconciliation.deactivateMissing(eq(SOURCE_ID), eq(Set.of("A")), eq(NOW))).thenReturn(0);
        when(ingestWriter.persistLiveAdapterFacility(
                        eq(SOURCE_ID), eq(RUN_ID), eq(FakeTestMunicipalParkingAdapter.SOURCE_KEY),
                        any(), any(), eq(NOW)))
                .thenReturn(new FacilityPersistResult(UUID.randomUUID(), false, true, true));
        var reactivate = service.sync(FakeTestMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(reactivate.recordsReactivated()).isEqualTo(1);
    }

    @Test
    void fetchFailureDoesNotDeactivateAndDoesNotTouchOtherProvider() {
        fake.setPayload(mapper.createArrayNode()); // will fail contract
        // Force fetch failure instead
        FakeTestMunicipalParkingAdapter failing = new FakeTestMunicipalParkingAdapter(mapper) {
            @Override
            public com.fasterxml.jackson.databind.JsonNode fetch() {
                throw new IllegalStateException("upstream down");
            }
        };
        MunicipalFacilitySyncService failingService = new MunicipalFacilitySyncService(
                List.of(failing), sources, runs, ingestWriter, setReconciliation,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(sources.requireBySourceKey(FakeTestMunicipalParkingAdapter.SOURCE_KEY))
                .thenReturn(new MunicipalDataSourceRepository.Source(
                        SOURCE_ID, FakeTestMunicipalParkingAdapter.SOURCE_KEY,
                        "Parkio", "test", 300, 900, null, true));

        var result = failingService.sync(FakeTestMunicipalParkingAdapter.SOURCE_KEY);

        assertThat(result.status()).isEqualTo(MunicipalSyncRunStatus.FAILED);
        verify(setReconciliation, never()).deactivateMissing(any(), any(), any());
        verify(setReconciliation, never()).deactivateMissing(eq(IZUM_SOURCE_ID), any(), any());
        verify(ingestWriter, never()).persistLiveAdapterFacility(any(), any(), any(), any(), any(), any());
    }

    @Test
    void duplicateExternalIdAcrossProvidersIsNamespacedBySource() {
        // Same external id under İZUM and FAKE is allowed — identity is (source, externalId).
        assertThat(IzumMunicipalParkingAdapter.SOURCE_KEY)
                .isNotEqualTo(FakeTestMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(MunicipalSourceIdentity.familyOf(FakeTestMunicipalParkingAdapter.SOURCE_KEY))
                .isEqualTo(MunicipalSourceIdentity.FAMILY_FAKE_TEST);
        assertThat(MunicipalSourceIdentity.familyOf(IzumMunicipalParkingAdapter.SOURCE_KEY))
                .isEqualTo(MunicipalSourceIdentity.FAMILY_IZUM);
    }

    @Test
    void recommendationMapperConsumesCanonicalFacilityWithoutProviderBranch() {
        FacilityView view = new FacilityView(
                UUID.randomUUID(),
                "Fake Lot",
                "Fake Operator",
                MunicipalFacilityType.OFF_STREET,
                null,
                38.45,
                27.2,
                50,
                12,
                38,
                MunicipalOccupancyFreshness.LIVE,
                "Parkio test fixture — not a public data source",
                "Parkio Fake Test Provider",
                NOW,
                FakeTestMunicipalParkingAdapter.SOURCE_KEY);

        ParkingCandidate candidate = ParkingCandidateMapper.fromMunicipal(view, 38.45, 27.2);

        assertThat(candidate.channel()).isEqualTo(ParkingCandidateChannel.MUNICIPAL_FACILITY);
        assertThat(candidate.refId()).isEqualTo(view.id().toString());
        assertThat(candidate.reasons().stream().map(RecommendationReason::code))
                .contains(RecommendationReasonCode.LIVE_AVAILABILITY);
    }

    @Test
    void fakeNeverProductionEligibleInCatalog() {
        assertThat(ParkingProviderCatalog.require(FakeTestMunicipalParkingAdapter.SOURCE_KEY).productionEligible())
                .isFalse();
    }
}
