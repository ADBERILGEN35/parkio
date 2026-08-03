package com.parkio.parking.application.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.MunicipalSourceHealthService;
import com.parkio.parking.application.MunicipalSourceSlaPolicy;
import com.parkio.parking.application.port.MunicipalDataSourceRepository;
import com.parkio.parking.application.port.MunicipalQualityReportQueryPort;
import com.parkio.parking.application.port.MunicipalSourceSyncRunRepository;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.MunicipalSourceOperationalState;
import com.parkio.parking.externalsource.izelman.IzelmanSourceKeys;
import com.parkio.parking.externalsource.registry.MunicipalQualityReportPolicy;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * DATA-WP-15 report assembly, purely against mocked read ports.
 *
 * <p>Guards the contract that matters operationally: coverage is numerator/denominator
 * with a null percentage on a zero denominator, provenance rows are emitted in a fixed
 * order, persisted import JSON is copied through an allow-list, and no code path
 * triggers a sync, import or linking write.
 */
class MunicipalQualityReportServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-03T10:00:00Z");
    private static final UUID OSM_SOURCE_ID = UUID.fromString("22222222-2222-2222-2222-222222222201");
    private static final UUID IZUM_SOURCE_ID = UUID.fromString("a1111111-1111-4111-8111-111111111101");
    private static final List<String> FORBIDDEN_COMPONENTS = List.of(
            "qualityscore", "trustscore", "readinessscore", "linkingreadiness", "productionready");

    private MunicipalQualityReportQueryPort queries;
    private MunicipalSourceHealthService healthService;
    private MunicipalSourceProperties properties;
    private MunicipalDataSourceRepository sources;
    private MunicipalSourceSyncRunRepository runs;
    private MunicipalQualityReportService service;

    @BeforeEach
    void setUp() {
        queries = mock(MunicipalQualityReportQueryPort.class);
        healthService = mock(MunicipalSourceHealthService.class);
        sources = mock(MunicipalDataSourceRepository.class);
        runs = mock(MunicipalSourceSyncRunRepository.class);
        properties = properties();
        service = new MunicipalQualityReportService(
                queries,
                healthService,
                properties,
                sources,
                runs,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper());

        stubHealth();
        stubSources();
        stubPopulatedRegistry();
    }

    // ---------------------------------------------------------------- overall report

    @Test
    void overallReportCarriesPolicyVersionBothSourcesAndBothSections() {
        MunicipalQualityReport report = service.overallReport();

        assertThat(report.policyVersion()).isEqualTo(MunicipalQualityReportPolicy.POLICY_VERSION);
        assertThat(report.generatedAt()).isEqualTo(NOW);
        assertThat(report.activeFacilities()).isEqualTo(10L);
        assertThat(report.sources()).extracting(SourceQualitySummary::sourceKey)
                .containsExactly(MunicipalSourceIdentity.OSM, MunicipalSourceIdentity.IZUM);
        assertThat(report.sources()).extracting(SourceQualitySummary::sourceFamily)
                .containsExactly(MunicipalSourceIdentity.FAMILY_OSM, MunicipalSourceIdentity.FAMILY_IZUM);
        assertThat(report.osm()).isNotNull();
        assertThat(report.izum()).isNotNull();
        assertThat(report.integrity()).isNotNull();
    }

    @Test
    void overallReportComputesPercentagesToTwoDecimals() {
        MunicipalQualityReport report = service.overallReport();

        SourceQualitySummary osm = report.sources().get(0);
        assertThat(osm.activeFacilities()).isEqualTo(6L);
        assertThat(osm.activeSourceLinks()).isEqualTo(7L);
        assertThat(osm.shareOfActiveFacilities())
                .isEqualTo(new CoverageMetric(6L, 10L, 60.0d));

        // 2 name-bearing outcomes out of 6 active OSM facilities -> 33.33 (HALF_UP at 2dp).
        assertThat(report.osm().nameBearingLabelCoverage())
                .isEqualTo(new CoverageMetric(2L, 6L, 33.33d));
        assertThat(report.izum().liveCoverage()).isEqualTo(new CoverageMetric(2L, 4L, 50.0d));
        assertThat(report.izum().agingCoverage()).isEqualTo(new CoverageMetric(1L, 4L, 25.0d));
        assertThat(report.izum().staleCoverage()).isEqualTo(new CoverageMetric(1L, 4L, 25.0d));
        assertThat(report.izum().availabilityExposedCoverage())
                .isEqualTo(new CoverageMetric(3L, 4L, 75.0d));
    }

    @Test
    void zeroDenominatorYieldsNullPercentageNotZeroOrHundred() {
        stubEmptyRegistry();

        MunicipalQualityReport report = service.overallReport();

        assertThat(report.activeFacilities()).isZero();
        assertThat(report.sources()).allSatisfy(summary -> {
            assertThat(summary.shareOfActiveFacilities().denominator()).isZero();
            assertThat(summary.shareOfActiveFacilities().percentage()).isNull();
            assertThat(summary.provenanceCoverage())
                    .allSatisfy(row -> assertThat(row.coverage().percentage()).isNull());
        });
        assertThat(report.osm().nameBearingLabelCoverage().percentage()).isNull();
        assertThat(report.osm().nullAvailabilityCoverage().percentage()).isNull();
        assertThat(report.izum().liveCoverage().percentage()).isNull();
        assertThat(CoverageMetric.of(3L, 0L)).isEqualTo(new CoverageMetric(3L, 0L, null));
        assertThat(CoverageMetric.of(1L, -5L)).isEqualTo(new CoverageMetric(1L, 0L, null));
    }

    @Test
    void osmSectionReportsLabelFactsAndZeroOccupancyByDesign() {
        OsmQualitySection osm = service.overallReport().osm();

        assertThat(osm.importEnabled()).isFalse();
        assertThat(osm.publicationEnabled()).isTrue();
        assertThat(osm.clipVersion()).isEqualTo("izmir-admin-izbb-2024-10-18-v1");
        assertThat(osm.labelPolicyVersion()).isEqualTo("osm-label-v1");
        assertThat(osm.activeFacilities()).isEqualTo(6L);
        assertThat(osm.technicalLabelCount()).isEqualTo(1L);
        assertThat(osm.staleNameMismatchCount()).isEqualTo(1L);
        assertThat(osm.occupancySnapshotCount()).isZero();
        assertThat(osm.nullAvailabilityCoverage()).isEqualTo(new CoverageMetric(6L, 6L, 100.0d));
    }

    @Test
    void labelOutcomeHistogramKeepsKnownOutcomesAndFoldsTheRestIntoUnknown() {
        when(queries.labelOutcomeHistogram()).thenReturn(List.of(
                new MunicipalQualityReportQueryPort.LabelOutcomeCount("real_name_selected", 2L),
                new MunicipalQualityReportQueryPort.LabelOutcomeCount("operator_fallback", 3L),
                new MunicipalQualityReportQueryPort.LabelOutcomeCount("legacy_technical", 1L),
                new MunicipalQualityReportQueryPort.LabelOutcomeCount("bogus_outcome", 4L),
                new MunicipalQualityReportQueryPort.LabelOutcomeCount(null, 5L),
                new MunicipalQualityReportQueryPort.LabelOutcomeCount("unknown", 6L)));

        Map<String, Long> outcomes = service.overallReport().osm().labelOutcomes();

        assertThat(outcomes)
                .containsEntry("real_name_selected", 2L)
                .containsEntry("operator_fallback", 3L)
                .containsEntry("legacy_technical", 1L)
                .containsEntry("unknown", 15L)
                .doesNotContainKey("bogus_outcome")
                .hasSize(4);
        assertThat(outcomes.keySet())
                .allSatisfy(key -> assertThat(
                                MunicipalQualityReportPolicy.KNOWN_LABEL_OUTCOMES.contains(key)
                                        || "unknown".equals(key))
                        .isTrue());
    }

    @Test
    void labelOutcomeHistogramOmitsUnknownBucketWhenEverythingIsKnown() {
        when(queries.labelOutcomeHistogram()).thenReturn(List.of(
                new MunicipalQualityReportQueryPort.LabelOutcomeCount("real_name_selected", 2L)));

        assertThat(service.overallReport().osm().labelOutcomes())
                .containsExactly(org.assertj.core.api.Assertions.entry("real_name_selected", 2L));
    }

    @Test
    void izumSectionUsesTheSourceRowThresholdsForBucketing() {
        MunicipalQualityReport report = service.overallReport();

        assertThat(report.izum().agingAfterSeconds()).isEqualTo(300L);
        assertThat(report.izum().staleAfterSeconds()).isEqualTo(900L);
        assertThat(report.izum().activeFacilities()).isEqualTo(4L);
        assertThat(report.izum().facilitiesWithOccupancy()).isEqualTo(4L);
        assertThat(report.izum().enabled()).isTrue();
        assertThat(report.izum().schedulerEnabled()).isFalse();

        verify(queries).countIzumFreshnessBuckets(300L, 900L, NOW);
    }

    @Test
    void izumSectionFallsBackToZeroThresholdsWhenTheSourceRowIsMissing() {
        when(sources.findBySourceKey(MunicipalSourceIdentity.IZUM)).thenReturn(Optional.empty());
        when(queries.countIzumFreshnessBuckets(0L, 0L, NOW)).thenReturn(
                new MunicipalQualityReportQueryPort.IzumFreshness(0L, 0L, 0L, 0L, 0L));

        IzumQualitySection izum = service.overallReport().izum();

        assertThat(izum.agingAfterSeconds()).isZero();
        assertThat(izum.staleAfterSeconds()).isZero();
        assertThat(izum.facilitiesWithOccupancy()).isZero();
    }

    @Test
    void integrityGuardrailsArePassedThroughUnchanged() {
        IntegrityGuardrails integrity = service.overallReport().integrity();

        assertThat(integrity).isEqualTo(new IntegrityGuardrails(0, 0, 3, 2, 1, 4, 5, 6, 2, 0));
        assertThat(integrity.osmOccupancySnapshots()).isZero();
    }

    // ---------------------------------------------------------------- provenance coverage

    @Test
    void provenanceCoverageIsEmittedInTheDeterministicPolicyOrder() {
        List<ProvenanceFieldCoverage> rows = service.overallReport().sources().get(0).provenanceCoverage();

        assertThat(rows).extracting(ProvenanceFieldCoverage::fieldName)
                .containsExactlyElementsOf(MunicipalQualityReportPolicy.PROVENANCE_FIELD_ORDER);
        // Repeating the call must produce byte-identical ordering.
        assertThat(service.overallReport().sources().get(0).provenanceCoverage()).isEqualTo(rows);
    }

    @Test
    void provenanceCoverageDropsFieldsOutsideTheAllowListAndFillsGapsWithZero() {
        when(queries.provenanceCoverageBySource(MunicipalSourceIdentity.OSM)).thenReturn(List.of(
                new MunicipalQualityReportQueryPort.FieldCoverage("NAME", 3L),
                new MunicipalQualityReportQueryPort.FieldCoverage("COORDINATES", 6L),
                new MunicipalQualityReportQueryPort.FieldCoverage("ATTRIBUTION", 6L),
                new MunicipalQualityReportQueryPort.FieldCoverage("ACCESS", 5L),
                new MunicipalQualityReportQueryPort.FieldCoverage("TARIFF_ASSIGNMENT", 4L),
                new MunicipalQualityReportQueryPort.FieldCoverage("OPENING_STATUS", 2L)));

        Map<String, ProvenanceFieldCoverage> byField = new LinkedHashMap<>();
        for (ProvenanceFieldCoverage row : service.overallReport().sources().get(0).provenanceCoverage()) {
            byField.put(row.fieldName(), row);
        }

        assertThat(byField.keySet()).doesNotContain("ACCESS", "TARIFF_ASSIGNMENT", "OPENING_STATUS");
        assertThat(byField.get("NAME").coverage()).isEqualTo(new CoverageMetric(3L, 6L, 50.0d));
        assertThat(byField.get("NAME").missing()).isEqualTo(3L);
        assertThat(byField.get("COORDINATES").coverage()).isEqualTo(new CoverageMetric(6L, 6L, 100.0d));
        assertThat(byField.get("COORDINATES").missing()).isZero();
        assertThat(byField.get("ADDRESS").coverage()).isEqualTo(new CoverageMetric(0L, 6L, 0.0d));
        assertThat(byField.get("ADDRESS").missing()).isEqualTo(6L);
    }

    @Test
    void duplicateProvenanceRowsForOneFieldAreSummedNotDropped() {
        when(queries.provenanceCoverageBySource(MunicipalSourceIdentity.OSM)).thenReturn(List.of(
                new MunicipalQualityReportQueryPort.FieldCoverage("NAME", 2L),
                new MunicipalQualityReportQueryPort.FieldCoverage("NAME", 3L)));

        assertThat(service.overallReport().sources().get(0).provenanceCoverage())
                .filteredOn(row -> row.fieldName().equals("NAME"))
                .singleElement()
                .extracting(row -> row.coverage().numerator())
                .isEqualTo(5L);
    }

    // ---------------------------------------------------------------- source detail

    @Test
    void osmSourceDetailPopulatesOnlyTheOsmSection() {
        SourceQualityDetail detail = service.sourceReport(MunicipalSourceIdentity.OSM, null);

        assertThat(detail.policyVersion()).isEqualTo(MunicipalQualityReportPolicy.POLICY_VERSION);
        assertThat(detail.generatedAt()).isEqualTo(NOW);
        assertThat(detail.summary().sourceKey()).isEqualTo(MunicipalSourceIdentity.OSM);
        assertThat(detail.osm()).isNotNull();
        assertThat(detail.izum()).isNull();
    }

    @Test
    void izumSourceDetailPopulatesOnlyTheIzumSection() {
        SourceQualityDetail detail = service.sourceReport(MunicipalSourceIdentity.IZUM, null);

        assertThat(detail.summary().sourceKey()).isEqualTo(MunicipalSourceIdentity.IZUM);
        assertThat(detail.izum()).isNotNull();
        assertThat(detail.osm()).isNull();
    }

    @Test
    void sourceDetailMapsRecentRunsNewestFirstWithoutIngestIdentifiers() {
        when(runs.findRecentCompleted(OSM_SOURCE_ID, 20)).thenReturn(List.of(
                new MunicipalSourceSyncRunRepository.CompletedRunView(
                        "SUCCESS", null, NOW.minusSeconds(120), NOW.minusSeconds(110)),
                new MunicipalSourceSyncRunRepository.CompletedRunView(
                        "FAILED", "read_timeout", NOW.minusSeconds(300), NOW.minusSeconds(290))));

        List<RecentSyncRunSummary> recent = service.sourceReport(MunicipalSourceIdentity.OSM, null).recentRuns();

        assertThat(recent).containsExactly(
                new RecentSyncRunSummary("SUCCESS", null, NOW.minusSeconds(120), NOW.minusSeconds(110)),
                new RecentSyncRunSummary("FAILED", "read_timeout",
                        NOW.minusSeconds(300), NOW.minusSeconds(290)));
        assertThat(RecentSyncRunSummary.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("status", "errorCategory", "startedAt", "completedAt");
    }

    @Test
    void sourceDetailReturnsNoRecentRunsWhenTheSourceRowIsMissing() {
        when(sources.findBySourceKey(MunicipalSourceIdentity.OSM)).thenReturn(Optional.empty());

        assertThat(service.sourceReport(MunicipalSourceIdentity.OSM, null).recentRuns()).isEmpty();
        verify(runs, never()).findRecentCompleted(any(), anyInt());
    }

    // ---------------------------------------------------------------- limits

    @Test
    void nullLimitUsesTheConfiguredDefault() {
        SourceQualityDetail detail = service.sourceReport(MunicipalSourceIdentity.OSM, null);

        assertThat(detail.recentRunLimit()).isEqualTo(20);
        verify(runs).findRecentCompleted(OSM_SOURCE_ID, 20);
    }

    @Test
    void explicitLimitInsideTheBoundIsHonoured() {
        assertThat(service.sourceReport(MunicipalSourceIdentity.OSM, 1).recentRunLimit()).isEqualTo(1);
        assertThat(service.sourceReport(MunicipalSourceIdentity.OSM, 100).recentRunLimit()).isEqualTo(100);
        verify(runs).findRecentCompleted(OSM_SOURCE_ID, 1);
        verify(runs).findRecentCompleted(OSM_SOURCE_ID, 100);
    }

    @Test
    void limitOutsideTheBoundIsRejectedBeforeAnyQueryRuns() {
        for (int invalid : new int[] {0, -1, -100, 101, 1000, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> service.sourceReport(MunicipalSourceIdentity.OSM, invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .isNotInstanceOf(UnknownQualityReportSourceException.class)
                    .hasMessage("limit must be between 1 and 100");
        }
        verify(runs, never()).findRecentCompleted(any(), anyInt());
        verify(queries, never()).countActiveFacilities();
    }

    @Test
    void defaultLimitIsClampedToTheConfiguredMaximum() {
        properties.getOps().setRecentRunLimitDefault(500);
        properties.getOps().setRecentRunLimitMax(50);

        assertThat(service.sourceReport(MunicipalSourceIdentity.OSM, null).recentRunLimit()).isEqualTo(50);
        assertThatThrownBy(() -> service.sourceReport(MunicipalSourceIdentity.OSM, 51))
                .hasMessage("limit must be between 1 and 50");
    }

    // ---------------------------------------------------------------- unsupported sources

    @Test
    void unsupportedSourceKeysAreRejectedAsUnknown() {
        for (String unsupported : new String[] {
                IzelmanSourceKeys.OPEN, IzelmanSourceKeys.TARIFFS, "", "  ",
                "OSM-GEOFABRIK-TURKEY", "../../etc/passwd"}) {
            assertThatThrownBy(() -> service.sourceReport(unsupported, null))
                    .isInstanceOf(UnknownQualityReportSourceException.class)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unsupported source key");
        }
        assertThatThrownBy(() -> service.sourceReport(null, null))
                .isInstanceOf(UnknownQualityReportSourceException.class);
        verify(runs, never()).findRecentCompleted(any(), anyInt());
        verify(queries, never()).countActiveFacilities();
    }

    @Test
    void unsupportedSourceIsRejectedBeforeTheLimitIsValidated() {
        assertThatThrownBy(() -> service.sourceReport(IzelmanSourceKeys.OPEN, 9999))
                .isInstanceOf(UnknownQualityReportSourceException.class);
    }

    // ---------------------------------------------------------------- import report normalisation

    @Test
    void importReportCopiesOnlyAllowListedKeysAndKnownOutcomes() {
        when(queries.latestOsmImportQualityReportJson()).thenReturn(Optional.of("""
                {
                  "named": 7,
                  "unnamed": 3,
                  "capacityKnown": 5,
                  "clipVersion": "izmir-admin-izbb-2024-10-18-v1",
                  "labelPolicyVersion": "osm-label-v1",
                  "rejectReasons": {"missing_geometry": 2, "outside_clip": 4, "bad": "not-a-number"},
                  "labelOutcomes": {"real_name_selected": 6, "operator_fallback": 2, "bogus_outcome": 9},
                  "qualityScore": 0.87,
                  "trustScore": 12,
                  "readinessScore": 3,
                  "linkingReadiness": "READY",
                  "productionReady": true,
                  "payloadSha256": "deadbeefdeadbeef",
                  "correlationId": "corr-123",
                  "rawPayload": "{...}"
                }
                """));

        NormalizedQualityReport normalized = service.overallReport().osm().latestImportReport();

        assertThat(normalized.present()).isTrue();
        assertThat(normalized.named()).isEqualTo(7L);
        assertThat(normalized.unnamed()).isEqualTo(3L);
        assertThat(normalized.capacityKnown()).isEqualTo(5L);
        assertThat(normalized.clipVersion()).isEqualTo("izmir-admin-izbb-2024-10-18-v1");
        assertThat(normalized.labelPolicyVersion()).isEqualTo("osm-label-v1");
        assertThat(normalized.rejectReasons())
                .containsOnlyKeys("missing_geometry", "outside_clip")
                .containsEntry("missing_geometry", 2L)
                .containsEntry("outside_clip", 4L);
        assertThat(normalized.labelOutcomes())
                .containsOnlyKeys("real_name_selected", "operator_fallback")
                .doesNotContainKey("bogus_outcome");

        assertThat(NormalizedQualityReport.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("present", "named", "unnamed", "capacityKnown",
                        "clipVersion", "labelPolicyVersion", "rejectReasons", "labelOutcomes");
    }

    @Test
    void importReportIsEmptyWhenAbsentBlankUnparseableOrNotAnObject() {
        for (Optional<String> raw : List.of(
                Optional.<String>empty(),
                Optional.of(""),
                Optional.of("   "),
                Optional.of("{not json"),
                Optional.of("[1,2,3]"),
                Optional.of("\"a string\""),
                Optional.of("42"))) {
            when(queries.latestOsmImportQualityReportJson()).thenReturn(raw);
            assertThat(service.overallReport().osm().latestImportReport())
                    .as("raw=%s", raw)
                    .isEqualTo(NormalizedQualityReport.empty());
        }
    }

    @Test
    void importReportEmptyProjectionCarriesNoValues() {
        NormalizedQualityReport empty = NormalizedQualityReport.empty();

        assertThat(empty.present()).isFalse();
        assertThat(empty.named()).isNull();
        assertThat(empty.unnamed()).isNull();
        assertThat(empty.capacityKnown()).isNull();
        assertThat(empty.clipVersion()).isNull();
        assertThat(empty.labelPolicyVersion()).isNull();
        assertThat(empty.rejectReasons()).isEmpty();
        assertThat(empty.labelOutcomes()).isEmpty();
    }

    @Test
    void importReportIgnoresNonNumericAndNonTextualValuesForTypedKeys() {
        when(queries.latestOsmImportQualityReportJson()).thenReturn(Optional.of("""
                {"named":"seven","unnamed":null,"capacityKnown":true,
                 "clipVersion":42,"labelPolicyVersion":{"v":1},
                 "rejectReasons":"not-an-object","labelOutcomes":[1,2]}
                """));

        NormalizedQualityReport normalized = service.overallReport().osm().latestImportReport();

        assertThat(normalized.present()).isTrue();
        assertThat(normalized.named()).isNull();
        assertThat(normalized.unnamed()).isNull();
        assertThat(normalized.capacityKnown()).isNull();
        assertThat(normalized.clipVersion()).isNull();
        assertThat(normalized.labelPolicyVersion()).isNull();
        assertThat(normalized.rejectReasons()).isEmpty();
        assertThat(normalized.labelOutcomes()).isEmpty();
    }

    @Test
    void importReportTruncatesOversizedTextToThePolicyBound() {
        String oversized = "c".repeat(MunicipalQualityReportPolicy.MAX_TEXT_LENGTH + 64);
        when(queries.latestOsmImportQualityReportJson()).thenReturn(Optional.of(
                "{\"clipVersion\":\"" + oversized + "\",\"labelPolicyVersion\":\"" + oversized + "\"}"));

        NormalizedQualityReport normalized = service.overallReport().osm().latestImportReport();

        assertThat(normalized.clipVersion()).hasSize(MunicipalQualityReportPolicy.MAX_TEXT_LENGTH);
        assertThat(normalized.labelPolicyVersion()).hasSize(MunicipalQualityReportPolicy.MAX_TEXT_LENGTH);
    }

    @Test
    void importReportCapsRejectReasonCardinality() {
        StringBuilder json = new StringBuilder("{\"rejectReasons\":{");
        int overflow = MunicipalQualityReportPolicy.MAX_REJECT_REASON_KEYS + 25;
        for (int i = 0; i < overflow; i++) {
            json.append(i > 0 ? "," : "").append("\"reason_").append(i).append("\":").append(i + 1);
        }
        json.append("}}");
        when(queries.latestOsmImportQualityReportJson()).thenReturn(Optional.of(json.toString()));

        assertThat(service.overallReport().osm().latestImportReport().rejectReasons())
                .hasSize(MunicipalQualityReportPolicy.MAX_REJECT_REASON_KEYS);
    }

    @Test
    void importReportLabelOutcomesAreCappedByTheKnownOutcomeCardinality() {
        Map<String, Long> everyKnown = new LinkedHashMap<>();
        for (String outcome : MunicipalQualityReportPolicy.KNOWN_LABEL_OUTCOMES) {
            everyKnown.put(outcome, 1L);
        }
        StringBuilder json = new StringBuilder("{\"labelOutcomes\":{");
        boolean first = true;
        for (String outcome : everyKnown.keySet()) {
            json.append(first ? "" : ",").append('"').append(outcome).append("\":1");
            first = false;
        }
        json.append(",\"bogus_a\":1,\"bogus_b\":2}}");
        when(queries.latestOsmImportQualityReportJson()).thenReturn(Optional.of(json.toString()));

        assertThat(service.overallReport().osm().latestImportReport().labelOutcomes())
                .hasSize(MunicipalQualityReportPolicy.KNOWN_LABEL_OUTCOMES.size())
                .containsOnlyKeys(everyKnown.keySet().toArray(String[]::new));
    }

    // ---------------------------------------------------------------- no scores, no writes

    @Test
    void noReportRecordExposesAnAggregateScoreOrReadinessVerdict() {
        List<Class<?>> records = List.of(
                MunicipalQualityReport.class,
                SourceQualityDetail.class,
                SourceQualitySummary.class,
                OsmQualitySection.class,
                IzumQualitySection.class,
                IntegrityGuardrails.class,
                CoverageMetric.class,
                ProvenanceFieldCoverage.class,
                RecentSyncRunSummary.class,
                NormalizedQualityReport.class);

        List<String> offending = new ArrayList<>();
        for (Class<?> type : records) {
            assertThat(type.isRecord()).as("%s is a record", type.getSimpleName()).isTrue();
            for (RecordComponent component : type.getRecordComponents()) {
                String folded = component.getName().toLowerCase(Locale.ROOT);
                if (FORBIDDEN_COMPONENTS.contains(folded)
                        || folded.endsWith("score")
                        || folded.contains("readiness")
                        || folded.contains("productionready")) {
                    offending.add(type.getSimpleName() + "." + component.getName());
                }
            }
        }
        assertThat(offending).isEmpty();
    }

    @Test
    void reportSerialisationLeaksNoScoreReadinessOrIngestIdentifier() throws Exception {
        when(runs.findRecentCompleted(OSM_SOURCE_ID, 20)).thenReturn(List.of(
                new MunicipalSourceSyncRunRepository.CompletedRunView(
                        "FAILED", "read_timeout", NOW.minusSeconds(60), NOW.minusSeconds(59))));
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

        String overall = mapper.writeValueAsString(service.overallReport());
        String detail = mapper.writeValueAsString(service.sourceReport(MunicipalSourceIdentity.OSM, null));

        for (String json : List.of(overall, detail)) {
            assertThat(json)
                    .doesNotContain("qualityScore")
                    .doesNotContain("trustScore")
                    .doesNotContain("readinessScore")
                    .doesNotContain("linkingReadiness")
                    .doesNotContain("productionReady")
                    .doesNotContain("correlationId")
                    .doesNotContain("payloadHash")
                    .doesNotContain("rawRecordHash")
                    .doesNotContain("schemaFingerprint");
        }
    }

    @Test
    void reportAssemblyOnlyReadsAndNeverTriggersASyncOrWrite() {
        service.overallReport();
        service.sourceReport(MunicipalSourceIdentity.OSM, null);
        service.sourceReport(MunicipalSourceIdentity.IZUM, null);

        verify(sources, never()).markSuccessful(any(), any());
        verify(sources, never()).requireBySourceKey(anyString());
        verify(runs, never()).tryStart(any(), anyString(), any());
        verify(runs, never()).complete(any(), any(), any(), any(), anyString());
        verify(runs, never()).countFailuresSince(any(), any());
        verify(runs, never()).countStaleRunning(any(), any());
        verify(runs, times(2)).findRecentCompleted(any(), anyInt());

        // The query port itself is read-only by construction: assert the contract, not the mock.
        assertThat(MunicipalQualityReportQueryPort.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .allSatisfy(name -> assertThat(name)
                        .matches("^(count|find|get|latest|technical|stale|label|provenance|integrity).*"));
    }

    @Test
    void repeatedOverallReportsAreIdenticalForUnchangedInputs() {
        MunicipalQualityReport first = service.overallReport();
        MunicipalQualityReport second = service.overallReport();

        assertThat(second).isEqualTo(first);
        assertThat(second.hashCode()).isEqualTo(first.hashCode());
    }

    @Test
    void osmSummaryStaysOnWhenOnlyPublicationIsEnabled() {
        properties.getOsm().setImportEnabled(false);
        properties.getOsm().setPublicationEnabled(true);

        service.overallReport();

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Boolean> enabled = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> scheduler = ArgumentCaptor.forClass(Boolean.class);
        verify(healthService, times(2)).snapshot(keys.capture(), enabled.capture(), scheduler.capture());

        assertThat(keys.getAllValues())
                .containsExactly(MunicipalSourceIdentity.OSM, MunicipalSourceIdentity.IZUM);
        assertThat(enabled.getAllValues()).containsExactly(true, true);
        assertThat(scheduler.getAllValues()).containsExactly(false, false);
    }

    @Test
    void osmSummaryIsOffWhenBothImportAndPublicationAreDisabled() {
        properties.getOsm().setImportEnabled(false);
        properties.getOsm().setPublicationEnabled(false);

        SourceQualitySummary osm = service.overallReport().sources().get(0);

        assertThat(osm.publicationEnabled()).isFalse();
        verify(healthService).snapshot(MunicipalSourceIdentity.OSM, false, false);
    }

    @Test
    void izumSummaryAlwaysReportsPublicationEnabled() {
        assertThat(service.overallReport().sources().get(1).publicationEnabled()).isTrue();
    }

    @Test
    void summaryMirrorsTheHealthSnapshotSlaFacts() {
        SourceQualitySummary osm = service.overallReport().sources().get(0);

        assertThat(osm.municipalEnabled()).isTrue();
        assertThat(osm.operationalState()).isEqualTo(MunicipalSourceOperationalState.DEGRADED.name());
        assertThat(osm.lastRunStatus()).isEqualTo("FAILED");
        assertThat(osm.consecutiveFailures()).isEqualTo(3);
        assertThat(osm.failuresInWindow()).isEqualTo(4);
        assertThat(osm.staleRunningOperations()).isZero();
        assertThat(osm.lastFailureCategory()).isEqualTo("read_timeout");
        assertThat(osm.secondsSinceSuccess()).isEqualTo(3600L);
        assertThat(osm.occupancyFreshness()).isEqualTo(MunicipalOccupancyFreshness.STALE.name());
    }

    // ---------------------------------------------------------------- fixtures

    private static MunicipalSourceProperties properties() {
        MunicipalSourceProperties props = new MunicipalSourceProperties();
        props.setEnabled(true);
        props.getOps().setQualityReportEnabled(true);
        props.getOps().setRecentRunLimitDefault(20);
        props.getOps().setRecentRunLimitMax(100);
        props.getOsm().setImportEnabled(false);
        props.getOsm().setSchedulerEnabled(false);
        props.getOsm().setPublicationEnabled(true);
        props.getOsm().setClipVersion("izmir-admin-izbb-2024-10-18-v1");
        props.getOsm().setLabelPolicy("osm-label-v1");
        props.getIzum().setEnabled(true);
        props.getIzum().setSchedulerEnabled(false);
        return props;
    }

    private void stubHealth() {
        when(healthService.snapshot(anyString(), anyBoolean(), anyBoolean()))
                .thenAnswer(invocation -> snapshot(invocation.getArgument(0)));
    }

    private void stubSources() {
        when(sources.findBySourceKey(MunicipalSourceIdentity.OSM))
                .thenReturn(Optional.of(source(OSM_SOURCE_ID, MunicipalSourceIdentity.OSM, 86400L, 604800L)));
        when(sources.findBySourceKey(MunicipalSourceIdentity.IZUM))
                .thenReturn(Optional.of(source(IZUM_SOURCE_ID, MunicipalSourceIdentity.IZUM, 300L, 900L)));
        when(runs.findRecentCompleted(any(), anyInt())).thenReturn(List.of());
    }

    private void stubPopulatedRegistry() {
        when(queries.countActiveFacilities()).thenReturn(10L);
        when(queries.countActiveFacilitiesBySourceKey(MunicipalSourceIdentity.OSM)).thenReturn(6L);
        when(queries.countActiveFacilitiesBySourceKey(MunicipalSourceIdentity.IZUM)).thenReturn(4L);
        when(queries.countActiveLinksBySourceKey(MunicipalSourceIdentity.OSM)).thenReturn(7L);
        when(queries.countActiveLinksBySourceKey(MunicipalSourceIdentity.IZUM)).thenReturn(4L);
        when(queries.countOsmOccupancy()).thenReturn(0L);
        when(queries.countOsmNullAvailability()).thenReturn(6L);
        when(queries.technicalLabelCount()).thenReturn(1L);
        when(queries.staleNameMismatchCount()).thenReturn(1L);
        when(queries.labelOutcomeHistogram()).thenReturn(List.of(
                new MunicipalQualityReportQueryPort.LabelOutcomeCount("real_name_selected", 2L),
                new MunicipalQualityReportQueryPort.LabelOutcomeCount("operator_fallback", 3L),
                new MunicipalQualityReportQueryPort.LabelOutcomeCount("legacy_technical", 1L)));
        when(queries.provenanceCoverageBySource(anyString())).thenReturn(List.of(
                new MunicipalQualityReportQueryPort.FieldCoverage("NAME", 3L),
                new MunicipalQualityReportQueryPort.FieldCoverage("COORDINATES", 6L)));
        when(queries.latestOsmImportQualityReportJson()).thenReturn(Optional.empty());
        when(queries.integrityCounts()).thenReturn(
                new MunicipalQualityReportQueryPort.IntegrityCounts(0, 0, 3, 2, 1, 4, 5, 6, 2, 0));
        when(queries.countIzumFreshnessBuckets(anyLong(), anyLong(), any()))
                .thenReturn(new MunicipalQualityReportQueryPort.IzumFreshness(2L, 1L, 1L, 3L, 4L));
    }

    private void stubEmptyRegistry() {
        when(queries.countActiveFacilities()).thenReturn(0L);
        when(queries.countActiveFacilitiesBySourceKey(anyString())).thenReturn(0L);
        when(queries.countActiveLinksBySourceKey(anyString())).thenReturn(0L);
        when(queries.countOsmOccupancy()).thenReturn(0L);
        when(queries.countOsmNullAvailability()).thenReturn(0L);
        when(queries.technicalLabelCount()).thenReturn(0L);
        when(queries.staleNameMismatchCount()).thenReturn(0L);
        when(queries.labelOutcomeHistogram()).thenReturn(List.of());
        when(queries.provenanceCoverageBySource(anyString())).thenReturn(List.of());
        when(queries.countIzumFreshnessBuckets(anyLong(), anyLong(), any()))
                .thenReturn(new MunicipalQualityReportQueryPort.IzumFreshness(0L, 0L, 0L, 0L, 0L));
    }

    private static MunicipalDataSourceRepository.Source source(
            UUID id, String sourceKey, long aging, long stale) {
        return new MunicipalDataSourceRepository.Source(
                id, sourceKey, "publisher", "attribution", aging, stale, NOW.minusSeconds(3600), true);
    }

    private static MunicipalSourceHealthService.Snapshot snapshot(String sourceKey) {
        return new MunicipalSourceHealthService.Snapshot(
                sourceKey,
                true,
                true,
                false,
                new MunicipalSourceSlaPolicy.Evaluation(
                        3,
                        "FAILED",
                        NOW.minusSeconds(60),
                        NOW.minusSeconds(3600),
                        3600L,
                        "read_timeout",
                        4,
                        0,
                        MunicipalSourceOperationalState.DEGRADED,
                        false),
                MunicipalOccupancyFreshness.STALE,
                300L,
                900L);
    }
}
