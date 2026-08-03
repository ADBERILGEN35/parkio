package com.parkio.parking.infrastructure.persistence.municipal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.quality.CoverageMetric;
import com.parkio.parking.application.quality.IzumQualitySection;
import com.parkio.parking.application.quality.MunicipalQualityReport;
import com.parkio.parking.application.quality.MunicipalQualityReportService;
import com.parkio.parking.application.quality.NormalizedQualityReport;
import com.parkio.parking.application.quality.OsmQualitySection;
import com.parkio.parking.application.quality.ProvenanceFieldCoverage;
import com.parkio.parking.application.quality.RecentSyncRunSummary;
import com.parkio.parking.application.quality.SourceQualityDetail;
import com.parkio.parking.application.quality.SourceQualitySummary;
import com.parkio.parking.application.quality.UnknownQualityReportSourceException;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.MunicipalSourceOperationalState;
import com.parkio.parking.externalsource.izelman.IzelmanSourceKeys;
import com.parkio.parking.externalsource.registry.MunicipalQualityReportPolicy;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * DATA-WP-15 quality report against real PostGIS data and the Flyway-seeded source registry.
 *
 * <p>Complements {@code MunicipalQualityReportServiceTest} (mocked ports) by proving the SQL in
 * {@code MunicipalQualityReportQueryAdapter} actually produces the aggregates the report claims:
 * label-outcome histogram, technical-label detection, İZUM freshness bucketing from the source-row
 * thresholds, provenance coverage with inactive facilities excluded, and the WP-06 failure streak.
 * Also asserts the report never mutates the registry and never serialises a score/readiness verdict.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class MunicipalQualityReportPostgresIT {
    private static final DockerImageName POSTGIS =
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres");

    // OSM facilities: one per label outcome under test, plus an inactive control.
    private static final UUID OSM_REAL = UUID.fromString("00000000-0000-0000-0000-000000009301");
    private static final UUID OSM_OPERATOR = UUID.fromString("00000000-0000-0000-0000-000000009302");
    private static final UUID OSM_TYPE = UUID.fromString("00000000-0000-0000-0000-000000009303");
    private static final UUID OSM_NEUTRAL = UUID.fromString("00000000-0000-0000-0000-000000009304");
    private static final UUID OSM_LEGACY = UUID.fromString("00000000-0000-0000-0000-000000009305");
    private static final UUID OSM_TECHNICAL_NAME = UUID.fromString("00000000-0000-0000-0000-000000009306");
    private static final UUID OSM_INACTIVE = UUID.fromString("00000000-0000-0000-0000-000000009307");

    // İZUM facilities: one per freshness bucket, plus one with no exposed availability.
    private static final UUID IZUM_LIVE = UUID.fromString("00000000-0000-0000-0000-000000009311");
    private static final UUID IZUM_AGING = UUID.fromString("00000000-0000-0000-0000-000000009312");
    private static final UUID IZUM_STALE = UUID.fromString("00000000-0000-0000-0000-000000009313");
    private static final UUID IZUM_LIVE_NO_AVAILABILITY =
            UUID.fromString("00000000-0000-0000-0000-000000009314");

    private static final UUID IZELMAN_FACILITY = UUID.fromString("00000000-0000-0000-0000-000000009321");

    private static final long ACTIVE_OSM_FACILITIES = 6L;
    private static final long ACTIVE_IZUM_FACILITIES = 4L;
    private static final long ACTIVE_FACILITIES = 11L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS)
            .withDatabaseName("parkio_quality_report_it")
            .withUsername("parkio")
            .withPassword("parkio");

    @Autowired MunicipalQualityReportService service;
    @Autowired MunicipalSourceProperties properties;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcClient jdbc;

    private Instant seededAt;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("parkio.kafka.provision-topics", () -> "false");
        registry.add("parkio.kafka.relay.enabled", () -> "false");
        registry.add("parkio.kafka.moderation-consumer.enabled", () -> "false");
        registry.add("parkio.kafka.ai-validation-consumer.enabled", () -> "false");
        registry.add("parkio.lifecycle.parking-expiry.enabled", () -> "false");
        registry.add("parkio.lifecycle.moderation-timeout.enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
        registry.add("parkio.municipal.enabled", () -> "true");
        registry.add("parkio.municipal.ops.quality-report-enabled", () -> "true");
        registry.add("parkio.municipal.izum.enabled", () -> "true");
        registry.add("parkio.municipal.izum.scheduler-enabled", () -> "false");
        registry.add("parkio.municipal.osm.publication-enabled", () -> "true");
        registry.add("parkio.municipal.osm.import-enabled", () -> "false");
        registry.add("parkio.municipal.osm.scheduler-enabled", () -> "false");
        // Keep every write path off: the report must never need them.
        registry.add("parkio.municipal.registry.automatic-linking-enabled", () -> "false");
        registry.add("parkio.municipal.registry.reviewed-linking-enabled", () -> "false");
        registry.add("parkio.municipal.registry.candidate-generation-enabled", () -> "false");
    }

    @BeforeEach
    void seed() {
        // Recent-run limits are read off a mutable singleton; restore the canonical defaults.
        properties.getOps().setRecentRunLimitDefault(20);
        properties.getOps().setRecentRunLimitMax(100);

        truncateMunicipalRegistry();
        seededAt = Instant.now();

        seedOsmFacilities();
        seedIzumFacilities();
        seedIzelmanFacility();
        seedProvenance();
        seedSyncRunHistory();
        seedOsmImportRuns();
    }

    // ------------------------------------------------------------------ overall shape

    @Test
    void overallReportCountsActiveFacilitiesAcrossBothSupportedSources() {
        MunicipalQualityReport report = service.overallReport();

        assertThat(report.policyVersion()).isEqualTo(MunicipalQualityReportPolicy.POLICY_VERSION);
        assertThat(report.activeFacilities()).isEqualTo(ACTIVE_FACILITIES);
        assertThat(report.sources()).extracting(SourceQualitySummary::sourceKey)
                .containsExactly(MunicipalSourceIdentity.OSM, MunicipalSourceIdentity.IZUM);

        SourceQualitySummary osm = summary(report, MunicipalSourceIdentity.OSM);
        assertThat(osm.activeFacilities()).isEqualTo(ACTIVE_OSM_FACILITIES);
        // The inactive facility keeps an active link: link count and facility count differ by design.
        assertThat(osm.activeSourceLinks()).isEqualTo(7L);
        assertThat(osm.shareOfActiveFacilities())
                .isEqualTo(new CoverageMetric(ACTIVE_OSM_FACILITIES, ACTIVE_FACILITIES, 54.55d));

        SourceQualitySummary izum = summary(report, MunicipalSourceIdentity.IZUM);
        assertThat(izum.activeFacilities()).isEqualTo(ACTIVE_IZUM_FACILITIES);
        assertThat(izum.activeSourceLinks()).isEqualTo(ACTIVE_IZUM_FACILITIES);
        assertThat(izum.shareOfActiveFacilities())
                .isEqualTo(new CoverageMetric(ACTIVE_IZUM_FACILITIES, ACTIVE_FACILITIES, 36.36d));

        // İZELMAN is not a reportable source even though it has an active linked facility.
        assertThat(report.sources()).extracting(SourceQualitySummary::sourceKey)
                .doesNotContain(IzelmanSourceKeys.OPEN);
    }

    @Test
    void inactiveFacilitiesAreExcludedFromEveryAggregate() {
        MunicipalQualityReport withInactive = service.overallReport();

        jdbc.sql("UPDATE municipal_parking_facilities SET active = TRUE WHERE id = :id")
                .param("id", OSM_INACTIVE)
                .update();
        MunicipalQualityReport activated = service.overallReport();

        assertThat(withInactive.activeFacilities()).isEqualTo(ACTIVE_FACILITIES);
        assertThat(activated.activeFacilities()).isEqualTo(ACTIVE_FACILITIES + 1);
        assertThat(activated.osm().activeFacilities()).isEqualTo(ACTIVE_OSM_FACILITIES + 1);
        // Reactivating adds its real_name_selected outcome and its ATTRIBUTION provenance row.
        assertThat(activated.osm().labelOutcomes().get("real_name_selected")).isEqualTo(2L);
        assertThat(coverage(activated, MunicipalSourceIdentity.OSM, "ATTRIBUTION").numerator())
                .isEqualTo(ACTIVE_OSM_FACILITIES + 1);
        assertThat(coverage(withInactive, MunicipalSourceIdentity.OSM, "ATTRIBUTION").numerator())
                .isEqualTo(ACTIVE_OSM_FACILITIES);
    }

    // ------------------------------------------------------------------ OSM section

    @Test
    void labelOutcomeHistogramMatchesPersistedLinkMetadata() {
        OsmQualitySection osm = service.overallReport().osm();

        assertThat(osm.labelOutcomes()).containsOnly(
                Map.entry("real_name_selected", 1L),
                Map.entry("operator_fallback", 1L),
                Map.entry("type_fallback", 1L),
                Map.entry("neutral_fallback", 1L),
                Map.entry("legacy_technical", 1L),
                Map.entry("unknown", 1L));
        assertThat(osm.activeFacilities()).isEqualTo(ACTIVE_OSM_FACILITIES);
        assertThat(osm.nameBearingLabelCoverage())
                .isEqualTo(new CoverageMetric(1L, ACTIVE_OSM_FACILITIES, 16.67d));
    }

    @Test
    void technicalLabelCountUnionsLegacyOutcomeAndTechnicalDisplayName() {
        // OSM_LEGACY matches on labelOutcome, OSM_TECHNICAL_NAME on the 'OSM parking %' display name.
        assertThat(service.overallReport().osm().technicalLabelCount()).isEqualTo(2L);
    }

    @Test
    void staleNameMismatchCountsFallbackLabelsThatStillCarryOsmNameProvenance() {
        OsmQualitySection before = service.overallReport().osm();
        assertThat(before.staleNameMismatchCount()).isEqualTo(1L);

        // A second fallback facility with OSM NAME provenance must be picked up too.
        insertProvenance(OSM_TYPE, "NAME", MunicipalSourceIdentity.OSM);
        assertThat(service.overallReport().osm().staleNameMismatchCount()).isEqualTo(2L);

        // A name-bearing outcome with NAME provenance is not a mismatch.
        insertProvenance(OSM_REAL, "NAME", MunicipalSourceIdentity.OSM);
        assertThat(service.overallReport().osm().staleNameMismatchCount()).isEqualTo(2L);
    }

    @Test
    void osmNeverExposesOccupancyAndReportsFullNullAvailabilityCoverage() {
        OsmQualitySection osm = service.overallReport().osm();

        assertThat(osm.occupancySnapshotCount()).isZero();
        assertThat(osm.nullAvailabilityCoverage()).isEqualTo(
                new CoverageMetric(ACTIVE_OSM_FACILITIES, ACTIVE_OSM_FACILITIES, 100.0d));
        assertThat(service.overallReport().integrity().osmOccupancySnapshots()).isZero();
        assertThat(count("""
                SELECT count(*) FROM municipal_occupancy_snapshots o
                JOIN municipal_data_sources s ON s.id = o.source_id
                WHERE s.source_key = 'osm-geofabrik-turkey'
                """)).isZero();
    }

    @Test
    void sourceLevelOccupancyFreshnessFollowsAuthorityNotSyncAge() {
        MunicipalQualityReport report = service.overallReport();
        SourceQualitySummary osm = summary(report, MunicipalSourceIdentity.OSM);
        SourceQualitySummary izum = summary(report, MunicipalSourceIdentity.IZUM);

        // OSM has successful import history age, but never contributes occupancy.
        assertThat(osm.occupancyFreshness()).isEqualTo("UNAVAILABLE");
        assertThat(report.osm().occupancySnapshotCount()).isZero();
        assertThat(report.osm().nullAvailabilityCoverage().numerator())
                .isEqualTo(ACTIVE_OSM_FACILITIES);

        // İZUM source freshness follows the newest occupancy observation (LIVE fixture).
        assertThat(izum.occupancyFreshness()).isEqualTo("LIVE");
        assertThat(report.izum().liveCoverage().numerator()).isEqualTo(2L);
        assertThat(report.izum().staleCoverage().numerator()).isEqualTo(1L);

        // Operational state remains independent of occupancy freshness.
        assertThat(osm.operationalState()).isNotEqualTo("UNKNOWN");
        assertThat(izum.operationalState()).isNotEqualTo("UNKNOWN");
    }

    @Test
    void osmSectionMirrorsTheConfiguredLabelAndClipPolicy() {
        OsmQualitySection osm = service.overallReport().osm();

        assertThat(osm.importEnabled()).isFalse();
        assertThat(osm.schedulerEnabled()).isFalse();
        assertThat(osm.publicationEnabled()).isTrue();
        assertThat(osm.clipVersion()).isEqualTo(properties.getOsm().getClipVersion());
        assertThat(osm.labelPolicyVersion()).isEqualTo(properties.getOsm().getLabelPolicy());
    }

    // ------------------------------------------------------------------ İZUM freshness

    @Test
    void izumFreshnessBucketsUseTheSeededSourceRowThresholds() {
        IzumQualitySection izum = service.overallReport().izum();

        // Flyway seeds izmir-izum-otoparklar with aging=300s and stale=900s.
        assertThat(izum.agingAfterSeconds()).isEqualTo(300L);
        assertThat(izum.staleAfterSeconds()).isEqualTo(900L);
        assertThat(izum.enabled()).isTrue();
        assertThat(izum.schedulerEnabled()).isFalse();
        assertThat(izum.activeFacilities()).isEqualTo(ACTIVE_IZUM_FACILITIES);
        // DISTINCT ON keeps exactly one (the newest) snapshot per facility.
        assertThat(izum.facilitiesWithOccupancy()).isEqualTo(ACTIVE_IZUM_FACILITIES);
        assertThat(count("SELECT count(*) FROM municipal_occupancy_snapshots"))
                .isEqualTo(ACTIVE_IZUM_FACILITIES + 1);

        assertThat(izum.liveCoverage()).isEqualTo(new CoverageMetric(2L, 4L, 50.0d));
        assertThat(izum.agingCoverage()).isEqualTo(new CoverageMetric(1L, 4L, 25.0d));
        assertThat(izum.staleCoverage()).isEqualTo(new CoverageMetric(1L, 4L, 25.0d));
        // The stale facility is excluded and the null-availability facility is not "exposed".
        assertThat(izum.availabilityExposedCoverage()).isEqualTo(new CoverageMetric(2L, 4L, 50.0d));
    }

    // ------------------------------------------------------------------ provenance coverage

    @Test
    void provenanceCoverageIsAllowListedOrderedAndScopedToActiveFacilities() {
        MunicipalQualityReport report = service.overallReport();
        List<ProvenanceFieldCoverage> osmRows = summary(report, MunicipalSourceIdentity.OSM)
                .provenanceCoverage();

        assertThat(osmRows).extracting(ProvenanceFieldCoverage::fieldName)
                .containsExactlyElementsOf(MunicipalQualityReportPolicy.PROVENANCE_FIELD_ORDER);
        assertThat(osmRows).extracting(ProvenanceFieldCoverage::fieldName)
                .doesNotContain("ACCESS", "DISTRICT", "TARIFF_ASSIGNMENT", "OPENING_STATUS");

        assertThat(coverage(report, MunicipalSourceIdentity.OSM, "NAME"))
                .isEqualTo(new CoverageMetric(1L, ACTIVE_OSM_FACILITIES, 16.67d));
        assertThat(coverage(report, MunicipalSourceIdentity.OSM, "COORDINATES"))
                .isEqualTo(new CoverageMetric(3L, ACTIVE_OSM_FACILITIES, 50.0d));
        assertThat(coverage(report, MunicipalSourceIdentity.OSM, "ATTRIBUTION"))
                .isEqualTo(new CoverageMetric(ACTIVE_OSM_FACILITIES, ACTIVE_OSM_FACILITIES, 100.0d));
        assertThat(coverage(report, MunicipalSourceIdentity.OSM, "OPERATOR"))
                .isEqualTo(new CoverageMetric(1L, ACTIVE_OSM_FACILITIES, 16.67d));
        assertThat(coverage(report, MunicipalSourceIdentity.OSM, "STATIC_CAPACITY"))
                .isEqualTo(new CoverageMetric(0L, ACTIVE_OSM_FACILITIES, 0.0d));
        assertThat(osmRows).filteredOn(row -> row.fieldName().equals("STATIC_CAPACITY"))
                .singleElement()
                .extracting(ProvenanceFieldCoverage::missing)
                .isEqualTo(ACTIVE_OSM_FACILITIES);

        assertThat(coverage(report, MunicipalSourceIdentity.IZUM, "NAME"))
                .isEqualTo(new CoverageMetric(4L, ACTIVE_IZUM_FACILITIES, 100.0d));
        assertThat(coverage(report, MunicipalSourceIdentity.IZUM, "ADDRESS"))
                .isEqualTo(new CoverageMetric(2L, ACTIVE_IZUM_FACILITIES, 50.0d));
    }

    // ------------------------------------------------------------------ WP-06 failure streak

    @Test
    void osmSummaryReportsTheConsecutiveFailureStreakFromPersistedRuns() {
        SourceQualitySummary osm = summary(service.overallReport(), MunicipalSourceIdentity.OSM);

        assertThat(osm.consecutiveFailures()).isEqualTo(3);
        assertThat(osm.lastRunStatus()).isEqualTo("FAILED");
        assertThat(osm.lastFailureCategory()).isEqualTo("read_timeout");
        assertThat(osm.failuresInWindow()).isEqualTo(3);
        // The RUNNING row is younger than the stale-operation threshold.
        assertThat(osm.staleRunningOperations()).isZero();
        // Measured from the newest SUCCESS run's completion instant, which is 4h - 5s old.
        assertThat(osm.secondsSinceSuccess()).isBetween(4L * 3600L - 60L, 4L * 3600L + 300L);
        assertThat(osm.lastSuccessAt()).isNotNull();
        assertThat(osm.lastRunAt()).isAfter(osm.lastSuccessAt());
        assertThat(MunicipalSourceOperationalState.valueOf(osm.operationalState()))
                .isIn(MunicipalSourceOperationalState.DEGRADED, MunicipalSourceOperationalState.CRITICAL);

        SourceQualitySummary izum = summary(service.overallReport(), MunicipalSourceIdentity.IZUM);
        assertThat(izum.consecutiveFailures()).isZero();
        assertThat(izum.lastRunStatus()).isEqualTo("SUCCESS");
        assertThat(izum.lastFailureCategory()).isNull();
    }

    // ------------------------------------------------------------------ source detail

    @Test
    void sourceDetailReturnsRecentRunsNewestFirstAndHonoursTheLimit() {
        SourceQualityDetail all = service.sourceReport(MunicipalSourceIdentity.OSM, null);

        assertThat(all.recentRunLimit()).isEqualTo(20);
        assertThat(all.osm()).isNotNull();
        assertThat(all.izum()).isNull();
        // RUNNING rows are never surfaced as completed history.
        assertThat(all.recentRuns()).hasSize(6);
        assertThat(all.recentRuns()).extracting(RecentSyncRunSummary::status)
                .containsExactly("FAILED", "FAILED", "FAILED", "SUCCESS", "SUCCESS", "SUCCESS");
        assertThat(all.recentRuns()).extracting(RecentSyncRunSummary::startedAt)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());

        SourceQualityDetail limited = service.sourceReport(MunicipalSourceIdentity.OSM, 2);
        assertThat(limited.recentRunLimit()).isEqualTo(2);
        assertThat(limited.recentRuns()).hasSize(2);
        assertThat(limited.recentRuns()).containsExactlyElementsOf(all.recentRuns().subList(0, 2));

        SourceQualityDetail izum = service.sourceReport(MunicipalSourceIdentity.IZUM, 5);
        assertThat(izum.osm()).isNull();
        assertThat(izum.izum()).isNotNull();
        assertThat(izum.recentRuns()).hasSize(1);
    }

    @Test
    void sourceDetailRejectsUnsupportedKeysAndOutOfBoundLimits() {
        assertThatThrownBy(() -> service.sourceReport(IzelmanSourceKeys.OPEN, null))
                .isInstanceOf(UnknownQualityReportSourceException.class);
        assertThatThrownBy(() -> service.sourceReport("does-not-exist", null))
                .isInstanceOf(UnknownQualityReportSourceException.class);
        assertThatThrownBy(() -> service.sourceReport(MunicipalSourceIdentity.OSM, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be between 1 and 100");
        assertThatThrownBy(() -> service.sourceReport(MunicipalSourceIdentity.OSM, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be between 1 and 100");
    }

    // ------------------------------------------------------------------ import report allow-list

    @Test
    void latestImportReportCopiesAllowListedKeysAndDropsForbiddenOnes() {
        NormalizedQualityReport normalized = service.overallReport().osm().latestImportReport();

        assertThat(normalized.present()).isTrue();
        assertThat(normalized.named()).isEqualTo(12L);
        assertThat(normalized.unnamed()).isEqualTo(4L);
        assertThat(normalized.capacityKnown()).isEqualTo(9L);
        // The newest sync run wins: the older import run carries a different clip version.
        assertThat(normalized.clipVersion()).isEqualTo("izmir-admin-izbb-2024-10-18-v1");
        assertThat(normalized.labelPolicyVersion()).isEqualTo("osm-label-v1");
        assertThat(normalized.rejectReasons()).containsOnly(
                Map.entry("missing_geometry", 3L), Map.entry("outside_clip", 5L));
        assertThat(normalized.labelOutcomes()).containsOnly(
                Map.entry("real_name_selected", 12L), Map.entry("operator_fallback", 4L));
        assertThat(normalized.labelOutcomes()).doesNotContainKey("totally_bogus");

        // The forbidden keys are physically present in the persisted row, so this is a real filter.
        String persisted = jdbc.sql("""
                SELECT r.quality_report_json
                FROM municipal_osm_import_runs r
                JOIN municipal_source_sync_runs sr ON sr.id = r.sync_run_id
                ORDER BY sr.started_at DESC LIMIT 1
                """).query(String.class).single();
        assertThat(persisted).contains("qualityScore", "linkingReadiness", "payloadSha256");
    }

    // ------------------------------------------------------------------ read-only and no leaks

    @Test
    void repeatedReportsAreStableAndLeaveTheRegistryUntouched() {
        Map<String, Long> before = registryCounts();

        MunicipalQualityReport first = service.overallReport();
        service.sourceReport(MunicipalSourceIdentity.OSM, null);
        service.sourceReport(MunicipalSourceIdentity.IZUM, null);
        MunicipalQualityReport second = service.overallReport();

        assertThat(registryCounts()).isEqualTo(before);
        // generatedAt and secondsSinceSuccess advance with the wall clock; nothing else may.
        assertThat(aggregateFingerprint(second)).isEqualTo(aggregateFingerprint(first));
        assertThat(second.osm()).isEqualTo(first.osm());
        assertThat(second.izum()).isEqualTo(first.izum());
        assertThat(second.integrity()).isEqualTo(first.integrity());
    }

    @Test
    void integrityGuardrailsCountIzelmanLinksAndFindNoDuplicates() {
        var integrity = service.overallReport().integrity();

        assertThat(integrity.izelmanLinkedActiveFacilities()).isEqualTo(1L);
        assertThat(integrity.osmOccupancySnapshots()).isZero();
        assertThat(integrity.duplicateSourceLinkGroups()).isZero();
        assertThat(integrity.duplicateProvenanceGroups()).isZero();
        assertThat(integrity.linkCandidates()).isZero();
        assertThat(integrity.pendingLinkCandidates()).isZero();
        assertThat(integrity.linkReviewDecisions()).isZero();
        assertThat(integrity.facilityAliases()).isZero();
        assertThat(integrity.activeTariffAssignments()).isZero();
    }

    @Test
    void serialisedReportsCarryNoScoreReadinessVerdictOrIngestIdentifier() throws Exception {
        String overall = objectMapper.writeValueAsString(service.overallReport());
        String osmDetail =
                objectMapper.writeValueAsString(service.sourceReport(MunicipalSourceIdentity.OSM, null));
        String izumDetail =
                objectMapper.writeValueAsString(service.sourceReport(MunicipalSourceIdentity.IZUM, null));

        for (String json : List.of(overall, osmDetail, izumDetail)) {
            assertThat(json)
                    .doesNotContain("qualityScore")
                    .doesNotContain("trustScore")
                    .doesNotContain("readinessScore")
                    .doesNotContain("linkingReadiness")
                    .doesNotContain("productionReady")
                    .doesNotContain("payloadSha256")
                    .doesNotContain("correlationId")
                    .doesNotContain("rawPayload")
                    .doesNotContain("rawRecordHash")
                    .doesNotContain("raw_record_hash")
                    .doesNotContain("externalId")
                    .doesNotContain("syncRunId");
            assertThat(json).contains(MunicipalQualityReportPolicy.POLICY_VERSION);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static SourceQualitySummary summary(MunicipalQualityReport report, String sourceKey) {
        return report.sources().stream()
                .filter(row -> row.sourceKey().equals(sourceKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing summary for " + sourceKey));
    }

    private static CoverageMetric coverage(
            MunicipalQualityReport report, String sourceKey, String fieldName) {
        return summary(report, sourceKey).provenanceCoverage().stream()
                .filter(row -> row.fieldName().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing coverage row " + fieldName))
                .coverage();
    }

    /**
     * Every aggregate in the report, excluding the two values that are functions of the wall
     * clock rather than of registry state ({@code generatedAt} and {@code secondsSinceSuccess}).
     */
    private static List<Object> aggregateFingerprint(MunicipalQualityReport report) {
        List<Object> perSource = report.sources().stream()
                .map(row -> (Object) List.of(
                        row.sourceKey(),
                        row.sourceFamily(),
                        row.activeFacilities(),
                        row.activeSourceLinks(),
                        row.shareOfActiveFacilities(),
                        row.provenanceCoverage(),
                        row.consecutiveFailures(),
                        row.failuresInWindow(),
                        row.staleRunningOperations(),
                        String.valueOf(row.lastRunStatus()),
                        String.valueOf(row.lastFailureCategory()),
                        row.occupancyFreshness()))
                .toList();
        return List.of(
                report.policyVersion(),
                report.activeFacilities(),
                perSource,
                report.osm(),
                report.izum(),
                report.integrity());
    }

    private Map<String, Long> registryCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : List.of(
                "municipal_parking_facilities",
                "municipal_facility_source_links",
                "municipal_facility_field_provenance",
                "municipal_occupancy_snapshots",
                "municipal_source_sync_runs",
                "municipal_osm_import_runs",
                "municipal_link_candidates",
                "municipal_link_review_audit",
                "municipal_facility_aliases",
                "municipal_tariff_assignments",
                "municipal_data_sources")) {
            counts.put(table, count("SELECT count(*) FROM " + table));
        }
        return counts;
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private void truncateMunicipalRegistry() {
        for (String table : List.of(
                "municipal_osm_import_runs",
                "municipal_izelman_import_runs",
                "municipal_occupancy_snapshots",
                "municipal_facility_field_provenance",
                "municipal_facility_conflation_decisions",
                "municipal_link_review_audit",
                "municipal_link_candidates",
                "municipal_facility_aliases",
                "municipal_tariff_assignments",
                "municipal_facility_source_links",
                "municipal_parking_facilities",
                "municipal_source_sync_runs")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        // Health falls back to run history only when the source row carries no success instant.
        jdbc.sql("UPDATE municipal_data_sources SET last_successful_sync_at = NULL").update();
    }

    private void seedOsmFacilities() {
        insertFacility(OSM_REAL, "Konak Otopark", 38.4200, 27.1400, true);
        insertFacility(OSM_OPERATOR, "IZELMAN Otopark", 38.4210, 27.1410, true);
        insertFacility(OSM_TYPE, "Multi-storey parking", 38.4220, 27.1420, true);
        insertFacility(OSM_NEUTRAL, "Parking", 38.4230, 27.1430, true);
        insertFacility(OSM_LEGACY, "OSM parking 4711", 38.4240, 27.1440, true);
        insertFacility(OSM_TECHNICAL_NAME, "OSM parking 8899", 38.4250, 27.1450, true);
        insertFacility(OSM_INACTIVE, "Retired Lot", 38.4260, 27.1460, false);

        insertLink(OSM_REAL, MunicipalSourceIdentity.OSM, "osm-node-1", labelMeta("real_name_selected"));
        insertLink(OSM_OPERATOR, MunicipalSourceIdentity.OSM, "osm-node-2", labelMeta("operator_fallback"));
        insertLink(OSM_TYPE, MunicipalSourceIdentity.OSM, "osm-node-3", labelMeta("type_fallback"));
        insertLink(OSM_NEUTRAL, MunicipalSourceIdentity.OSM, "osm-node-4", labelMeta("neutral_fallback"));
        insertLink(OSM_LEGACY, MunicipalSourceIdentity.OSM, "osm-node-5", labelMeta("legacy_technical"));
        // No labelOutcome key at all -> folded into the bounded "unknown" bucket.
        insertLink(OSM_TECHNICAL_NAME, MunicipalSourceIdentity.OSM, "osm-node-6", "{\"district\":\"Konak\"}");
        insertLink(OSM_INACTIVE, MunicipalSourceIdentity.OSM, "osm-node-7", labelMeta("real_name_selected"));
    }

    private void seedIzumFacilities() {
        insertFacility(IZUM_LIVE, "IZUM Live Lot", 38.4300, 27.1500, true);
        insertFacility(IZUM_AGING, "IZUM Aging Lot", 38.4310, 27.1510, true);
        insertFacility(IZUM_STALE, "IZUM Stale Lot", 38.4320, 27.1520, true);
        insertFacility(IZUM_LIVE_NO_AVAILABILITY, "IZUM Blind Lot", 38.4330, 27.1530, true);

        insertLink(IZUM_LIVE, MunicipalSourceIdentity.IZUM, "izum-1", "{}");
        insertLink(IZUM_AGING, MunicipalSourceIdentity.IZUM, "izum-2", "{}");
        insertLink(IZUM_STALE, MunicipalSourceIdentity.IZUM, "izum-3", "{}");
        insertLink(IZUM_LIVE_NO_AVAILABILITY, MunicipalSourceIdentity.IZUM, "izum-4", "{}");
    }

    private void seedIzelmanFacility() {
        insertFacility(IZELMAN_FACILITY, "IZELMAN Open Lot", 38.4400, 27.1600, true);
        insertLink(IZELMAN_FACILITY, IzelmanSourceKeys.OPEN, "izelman-1", "{}");
    }

    private void seedProvenance() {
        insertProvenance(OSM_OPERATOR, "NAME", MunicipalSourceIdentity.OSM);
        insertProvenance(OSM_REAL, "COORDINATES", MunicipalSourceIdentity.OSM);
        insertProvenance(OSM_OPERATOR, "COORDINATES", MunicipalSourceIdentity.OSM);
        insertProvenance(OSM_TYPE, "COORDINATES", MunicipalSourceIdentity.OSM);
        insertProvenance(OSM_REAL, "OPERATOR", MunicipalSourceIdentity.OSM);
        for (UUID facility : List.of(
                OSM_REAL, OSM_OPERATOR, OSM_TYPE, OSM_NEUTRAL, OSM_LEGACY, OSM_TECHNICAL_NAME,
                OSM_INACTIVE)) {
            insertProvenance(facility, "ATTRIBUTION", MunicipalSourceIdentity.OSM);
        }
        // Outside the public allow-list: must never reach the report.
        insertProvenance(OSM_REAL, "ACCESS", MunicipalSourceIdentity.OSM);
        insertProvenance(OSM_REAL, "DISTRICT", MunicipalSourceIdentity.OSM);
        insertProvenance(OSM_TYPE, "TARIFF_ASSIGNMENT", MunicipalSourceIdentity.OSM);
        insertProvenance(OSM_NEUTRAL, "OPENING_STATUS", MunicipalSourceIdentity.OSM);

        for (UUID facility : List.of(IZUM_LIVE, IZUM_AGING, IZUM_STALE, IZUM_LIVE_NO_AVAILABILITY)) {
            insertProvenance(facility, "NAME", MunicipalSourceIdentity.IZUM);
            insertProvenance(facility, "COORDINATES", MunicipalSourceIdentity.IZUM);
        }
        insertProvenance(IZUM_LIVE, "ADDRESS", MunicipalSourceIdentity.IZUM);
        insertProvenance(IZUM_AGING, "ADDRESS", MunicipalSourceIdentity.IZUM);
    }

    private void seedSyncRunHistory() {
        // OSM: three failures on top of three successes -> consecutiveFailures = 3 (WP-06).
        insertCompletedRun(MunicipalSourceIdentity.OSM, "SUCCESS", null, seededAt.minusSeconds(6 * 3600));
        insertCompletedRun(MunicipalSourceIdentity.OSM, "SUCCESS", null, seededAt.minusSeconds(5 * 3600));
        insertCompletedRun(MunicipalSourceIdentity.OSM, "SUCCESS", null, seededAt.minusSeconds(4 * 3600));
        insertCompletedRun(
                MunicipalSourceIdentity.OSM, "FAILED", "schema_contract", seededAt.minusSeconds(3 * 3600));
        insertCompletedRun(
                MunicipalSourceIdentity.OSM, "FAILED", "read_timeout", seededAt.minusSeconds(2 * 3600));
        insertCompletedRun(
                MunicipalSourceIdentity.OSM, "FAILED", "read_timeout", seededAt.minusSeconds(3600));
        // Younger than the 600s stale-operation threshold and excluded from completed history.
        insertRunningRun(MunicipalSourceIdentity.OSM, seededAt.minusSeconds(30));

        insertCompletedRun(MunicipalSourceIdentity.IZUM, "SUCCESS", null, seededAt.minusSeconds(30));
        UUID izumRun = latestRunId(MunicipalSourceIdentity.IZUM);
        insertOccupancy(IZUM_LIVE, izumRun, seededAt.minusSeconds(5000), 7, "izum-live-old");
        insertOccupancy(IZUM_LIVE, izumRun, seededAt.minusSeconds(30), 100, "izum-live-new");
        insertOccupancy(IZUM_AGING, izumRun, seededAt.minusSeconds(600), 50, "izum-aging");
        insertOccupancy(IZUM_STALE, izumRun, seededAt.minusSeconds(3600), 10, "izum-stale");
        insertOccupancy(IZUM_LIVE_NO_AVAILABILITY, izumRun, seededAt.minusSeconds(60), null, "izum-blind");
    }

    private void seedOsmImportRuns() {
        UUID oldRun = runIdByCorrelation(MunicipalSourceIdentity.OSM, 6 * 3600);
        UUID newRun = runIdByCorrelation(MunicipalSourceIdentity.OSM, 5 * 3600);
        insertOsmImportRun(oldRun, "izmir-admin-izbb-2023-01-01-v0", """
                {"named":1,"unnamed":1,"capacityKnown":1,
                 "clipVersion":"izmir-admin-izbb-2023-01-01-v0",
                 "labelPolicyVersion":"legacy"}
                """);
        insertOsmImportRun(newRun, "izmir-admin-izbb-2024-10-18-v1", """
                {"named":12,"unnamed":4,"capacityKnown":9,
                 "clipVersion":"izmir-admin-izbb-2024-10-18-v1",
                 "labelPolicyVersion":"osm-label-v1",
                 "rejectReasons":{"missing_geometry":3,"outside_clip":5},
                 "labelOutcomes":{"real_name_selected":12,"operator_fallback":4,"totally_bogus":99},
                 "qualityScore":0.91,
                 "trustScore":7,
                 "readinessScore":2,
                 "linkingReadiness":"READY",
                 "productionReady":true,
                 "payloadSha256":"deadbeefdeadbeefdeadbeefdeadbeef",
                 "correlationId":"corr-osm-import-1",
                 "rawPayload":"{\\"elements\\":[]}"}
                """);
    }

    private static String labelMeta(String outcome) {
        return "{\"labelOutcome\":\"" + outcome + "\",\"district\":\"Konak\"}";
    }

    private void insertFacility(UUID id, String displayName, double lat, double lng, boolean active) {
        jdbc.sql("""
                INSERT INTO municipal_parking_facilities (
                    id,operator_name,facility_type,access_classification,display_name,address_text,
                    latitude,longitude,location,capacity_total,active,lifecycle_state,created_at,updated_at)
                VALUES (:id,'IZELMAN','OFF_STREET','PUBLIC',:name,'Konak',
                    :lat,:lng,ST_SetSRID(ST_MakePoint(:lng,:lat),4326)::geography,100,:active,
                    'ACTIVE',now(),now())
                """)
                .param("id", id)
                .param("name", displayName)
                .param("lat", lat)
                .param("lng", lng)
                .param("active", active)
                .update();
    }

    private void insertLink(UUID facilityId, String sourceKey, String externalId, String metadataJson) {
        jdbc.sql("""
                INSERT INTO municipal_facility_source_links (
                    id,facility_id,source_id,external_id,source_name,source_metadata_json,raw_record_hash,
                    first_seen_at,last_seen_at,last_successful_sync_at,active,created_at,updated_at)
                SELECT :id,:facility,id,:external,:name,:metadata,:hash,
                    now(),now(),now(),TRUE,now(),now()
                FROM municipal_data_sources WHERE source_key = :sourceKey
                """)
                .param("id", UUID.randomUUID())
                .param("facility", facilityId)
                .param("external", externalId)
                .param("name", "facility")
                .param("metadata", metadataJson)
                .param("hash", externalId + "-hash")
                .param("sourceKey", sourceKey)
                .update();
    }

    private void insertProvenance(UUID facilityId, String field, String sourceKey) {
        jdbc.sql("""
                INSERT INTO municipal_facility_field_provenance (
                    id,facility_id,field_name,source_key,source_record_id,source_content_ts,fetch_ts,
                    source_age_class,confidence_or_review_state,selection_reason,last_selected_at,version)
                VALUES (:id,:facility,:field,:sourceKey,:recordId,now(),now(),
                    'CURRENT','CURRENT','quality-report-it',now(),0)
                """)
                .param("id", UUID.randomUUID())
                .param("facility", facilityId)
                .param("field", field)
                .param("sourceKey", sourceKey)
                .param("recordId", sourceKey + "-" + field + "-" + facilityId)
                .update();
    }

    private void insertCompletedRun(String sourceKey, String status, String category, Instant startedAt) {
        jdbc.sql("""
                INSERT INTO municipal_source_sync_runs (
                    id,source_id,correlation_id,started_at,completed_at,status,records_received,
                    records_accepted,records_rejected,records_inserted,records_updated,records_unchanged,
                    occupancy_inserted,error_category)
                SELECT :id,id,:correlation,:startedAt,:completedAt,:status,0,0,0,0,0,0,0,:category
                FROM municipal_data_sources WHERE source_key = :sourceKey
                """)
                .param("id", UUID.randomUUID())
                .param("correlation", correlationFor(sourceKey, startedAt))
                .param("startedAt", Timestamp.from(startedAt))
                .param("completedAt", Timestamp.from(startedAt.plusSeconds(5)))
                .param("status", status)
                .param("category", category)
                .param("sourceKey", sourceKey)
                .update();
    }

    private void insertRunningRun(String sourceKey, Instant startedAt) {
        jdbc.sql("""
                INSERT INTO municipal_source_sync_runs (
                    id,source_id,correlation_id,started_at,status,records_received,records_accepted,
                    records_rejected,records_inserted,records_updated,records_unchanged,occupancy_inserted)
                SELECT :id,id,:correlation,:startedAt,'RUNNING',0,0,0,0,0,0,0
                FROM municipal_data_sources WHERE source_key = :sourceKey
                """)
                .param("id", UUID.randomUUID())
                .param("correlation", correlationFor(sourceKey, startedAt))
                .param("startedAt", Timestamp.from(startedAt))
                .param("sourceKey", sourceKey)
                .update();
    }

    private void insertOccupancy(
            UUID facilityId, UUID syncRunId, Instant fetchedAt, Integer availableSpaces, String hash) {
        jdbc.sql("""
                INSERT INTO municipal_occupancy_snapshots (
                    id,facility_id,source_id,source_link_id,sync_run_id,source_observed_at,fetched_at,
                    timestamp_provenance,capacity_total,occupied_spaces,available_spaces,occupancy_status,
                    raw_record_hash,created_at)
                SELECT :id,:facility,s.id,l.id,:runId,:fetchedAt,:fetchedAt,
                    'SOURCE',100,NULL,:available,'LIVE',:hash,now()
                FROM municipal_data_sources s
                JOIN municipal_facility_source_links l
                  ON l.source_id = s.id AND l.facility_id = :facility
                WHERE s.source_key = :sourceKey
                """)
                .param("id", UUID.randomUUID())
                .param("facility", facilityId)
                .param("runId", syncRunId)
                .param("fetchedAt", Timestamp.from(fetchedAt))
                .param("available", availableSpaces)
                .param("hash", hash)
                .param("sourceKey", MunicipalSourceIdentity.IZUM)
                .update();
    }

    private void insertOsmImportRun(UUID syncRunId, String clipVersion, String qualityReportJson) {
        jdbc.sql("""
                INSERT INTO municipal_osm_import_runs (
                    id,sync_run_id,input_filename,sha256,import_config_version,clip_version,
                    quality_report_json,created_at)
                VALUES (:id,:runId,'turkey-latest.osm.pbf',:sha,'osm-import-v1',:clip,:report,now())
                """)
                .param("id", UUID.randomUUID())
                .param("runId", syncRunId)
                .param("sha", "sha-" + clipVersion)
                .param("clip", clipVersion)
                .param("report", qualityReportJson)
                .update();
    }

    private UUID latestRunId(String sourceKey) {
        return jdbc.sql("""
                SELECT r.id FROM municipal_source_sync_runs r
                JOIN municipal_data_sources s ON s.id = r.source_id
                WHERE s.source_key = :sourceKey
                ORDER BY r.started_at DESC LIMIT 1
                """).param("sourceKey", sourceKey).query(UUID.class).single();
    }

    private UUID runIdByCorrelation(String sourceKey, long secondsAgo) {
        return jdbc.sql("SELECT id FROM municipal_source_sync_runs WHERE correlation_id = :correlation")
                .param("correlation", correlationFor(sourceKey, seededAt.minusSeconds(secondsAgo)))
                .query(UUID.class)
                .single();
    }

    private static String correlationFor(String sourceKey, Instant startedAt) {
        return "qr-it-" + sourceKey + "-" + startedAt.toEpochMilli();
    }
}
