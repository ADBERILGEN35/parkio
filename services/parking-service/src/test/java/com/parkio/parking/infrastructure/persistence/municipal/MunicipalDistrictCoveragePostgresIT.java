package com.parkio.parking.infrastructure.persistence.municipal;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.quality.DistrictCoverageEntry;
import com.parkio.parking.application.quality.DistrictCoverageSection;
import com.parkio.parking.application.quality.DistrictCoverageStatus;
import com.parkio.parking.application.quality.MunicipalDistrictCoverageReason;
import com.parkio.parking.application.quality.MunicipalDistrictCoverageAssembler;
import com.parkio.parking.application.quality.MunicipalQualityReport;
import com.parkio.parking.application.quality.MunicipalQualityReportService;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.district.MunicipalDistrictCoveragePolicy;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
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
 * DATA-WP-18 district coverage against real PostGIS data, Flyway-seeded sources and the
 * miniature İZBB district fixture.
 *
 * <p>Complements {@code MunicipalDistrictCoverageAssemblerTest} (mocked query port) by proving
 * {@code MunicipalQualityReportQueryAdapter#listActiveFacilityProjections} and polygon assignment
 * produce the aggregates the overall report exposes.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class MunicipalDistrictCoveragePostgresIT {
    private static final DockerImageName POSTGIS =
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres");

    private static final String EXPECTED_MINIATURE_SHA256 =
            "bda50f443b8226f383ad558a87cd940278e571e9d3d399a28cdd8deca7dff555";

    private static final Path DISTRICT_ASSET_PATH;
    private static final String DISTRICT_ASSET_SHA256;

    static {
        try {
            byte[] bytes;
            try (InputStream in = MunicipalDistrictCoveragePostgresIT.class.getResourceAsStream(
                    "/fixtures/municipal/boundary/ilceler-official-miniature.geojson")) {
                if (in == null) {
                    throw new IllegalStateException("missing miniature district fixture");
                }
                bytes = in.readAllBytes();
            }
            DISTRICT_ASSET_PATH = Files.createTempFile("ilceler-official-miniature-", ".geojson");
            Files.write(DISTRICT_ASSET_PATH, bytes);
            DISTRICT_ASSET_SHA256 =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            assertThat(DISTRICT_ASSET_SHA256).isEqualTo(EXPECTED_MINIATURE_SHA256);
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static final UUID KONAK_OSM = UUID.fromString("00000000-0000-0000-0000-000000009401");
    private static final UUID KONAK_OSM_INACTIVE = UUID.fromString("00000000-0000-0000-0000-000000009402");
    private static final UUID BORNOVA_IZUM = UUID.fromString("00000000-0000-0000-0000-000000009403");
    private static final UUID UNASSIGNED_FACILITY = UUID.fromString("00000000-0000-0000-0000-000000009404");

    private static final long ACTIVE_FACILITIES = 3L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS)
            .withDatabaseName("parkio_district_coverage_it")
            .withUsername("parkio")
            .withPassword("parkio");

    @Autowired MunicipalQualityReportService service;
    @Autowired MunicipalDistrictCoverageAssembler districtCoverageAssembler;
    @Autowired MunicipalSourceProperties properties;
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
        registry.add("parkio.municipal.ops.district-coverage-enabled", () -> "true");
        registry.add("parkio.municipal.ops.district-coverage.asset-path", () -> DISTRICT_ASSET_PATH.toString());
        registry.add("parkio.municipal.ops.district-coverage.expected-sha256", () -> DISTRICT_ASSET_SHA256);
        registry.add("parkio.municipal.ops.district-coverage.expected-count", () -> "30");
        registry.add("parkio.municipal.ops.district-coverage.name-property", () -> "adi");
        registry.add("parkio.municipal.ops.district-coverage.max-facilities", () -> "10000");
        registry.add("parkio.municipal.ops.district-coverage.cache-ttl-seconds", () -> "45");
        registry.add("parkio.municipal.izum.enabled", () -> "true");
        registry.add("parkio.municipal.izum.scheduler-enabled", () -> "false");
        registry.add("parkio.municipal.osm.publication-enabled", () -> "true");
        registry.add("parkio.municipal.osm.import-enabled", () -> "false");
        registry.add("parkio.municipal.osm.scheduler-enabled", () -> "false");
        registry.add("parkio.municipal.registry.automatic-linking-enabled", () -> "false");
        registry.add("parkio.municipal.registry.reviewed-linking-enabled", () -> "false");
        registry.add("parkio.municipal.registry.candidate-generation-enabled", () -> "false");
    }

    @BeforeEach
    void seed() {
        properties.getOps().getDistrictCoverage().setMaxFacilities(10_000);
        districtCoverageAssembler.clearCache();

        truncateMunicipalRegistry();
        seededAt = Instant.now();

        insertFacility(KONAK_OSM, "Konak OSM Lot", 38.755, 27.005, true);
        insertFacility(KONAK_OSM_INACTIVE, "Konak Retired Lot", 38.755, 27.006, false);
        insertFacility(BORNOVA_IZUM, "Bornova IZUM Lot", 38.255, 26.405, true);
        insertFacility(UNASSIGNED_FACILITY, "Unassigned Lot", 38.1, 26.5, true);

        insertLink(KONAK_OSM, MunicipalSourceIdentity.OSM, "osm-konak-1", labelMeta("real_name_selected"));
        insertLink(KONAK_OSM_INACTIVE, MunicipalSourceIdentity.OSM, "osm-konak-inactive", labelMeta("real_name_selected"));
        insertLink(BORNOVA_IZUM, MunicipalSourceIdentity.IZUM, "izum-bornova-1", "{}");
        insertLink(UNASSIGNED_FACILITY, MunicipalSourceIdentity.OSM, "osm-unassigned-1", labelMeta("neutral_fallback"));

        insertProvenance(KONAK_OSM, "NAME", MunicipalSourceIdentity.OSM);
        insertProvenance(BORNOVA_IZUM, "NAME", MunicipalSourceIdentity.IZUM);

        insertCompletedRun(MunicipalSourceIdentity.IZUM, "SUCCESS", null, seededAt.minusSeconds(30));
        UUID izumRun = latestRunId(MunicipalSourceIdentity.IZUM);
        insertOccupancy(BORNOVA_IZUM, izumRun, seededAt.minusSeconds(30), 42, "izum-bornova-live");
    }

    @Test
    void districtCoverageIsAvailableWithAllDistrictRowsIncludingZeros() {
        DistrictCoverageSection section = service.overallReport().districtCoverage();

        assertThat(section.status()).isEqualTo(DistrictCoverageStatus.AVAILABLE);
        assertThat(section.unavailableReason()).isNull();
        assertThat(section.policyVersion()).isEqualTo(MunicipalDistrictCoveragePolicy.POLICY_VERSION);
        assertThat(section.assetVersion()).isEqualTo(MunicipalDistrictCoveragePolicy.ASSET_VERSION);
        assertThat(section.districtCount()).isEqualTo(30);
        assertThat(section.districts()).hasSize(30);
        assertThat(section.districts()).extracting(DistrictCoverageEntry::totalActiveFacilities)
                .contains(0L);
    }

    @Test
    void konakAndBornovaReceiveExpectedSourceCounts() {
        DistrictCoverageSection section = service.overallReport().districtCoverage();

        DistrictCoverageEntry konak = district(section, "KONAK");
        assertThat(konak.activeOsmFacilities()).isGreaterThanOrEqualTo(1);
        assertThat(konak.activeIzumFacilities()).isZero();

        DistrictCoverageEntry bornova = district(section, "BORNOVA");
        assertThat(bornova.activeIzumFacilities()).isGreaterThanOrEqualTo(1);
        assertThat(bornova.activeOsmFacilities()).isZero();
        assertThat(bornova.availabilityExposedIzumFacilities()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void unassignedFacilitiesAreCountedOutsideDistrictGeometry() {
        DistrictCoverageSection section = service.overallReport().districtCoverage();

        assertThat(section.unassignedFacilityCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void assignmentBucketsSumToActiveFacilityCountConsidered() {
        DistrictCoverageSection section = service.overallReport().districtCoverage();

        assertThat(section.activeFacilityCountConsidered()).isEqualTo(ACTIVE_FACILITIES);
        assertThat(section.assignedFacilityCount()
                        + section.unassignedFacilityCount()
                        + section.invalidCoordinateCount())
                .isEqualTo(section.activeFacilityCountConsidered());
        assertThat(section.assignedFacilityCount()).isEqualTo(2L);
        assertThat(section.unassignedFacilityCount()).isEqualTo(1L);
        assertThat(section.invalidCoordinateCount()).isZero();
    }

    @Test
    void inactiveFacilitiesAreExcludedFromDistrictCoverage() {
        DistrictCoverageSection withInactive = service.overallReport().districtCoverage();
        assertThat(withInactive.activeFacilityCountConsidered()).isEqualTo(ACTIVE_FACILITIES);

        jdbc.sql("UPDATE municipal_parking_facilities SET active = TRUE WHERE id = :id")
                .param("id", KONAK_OSM_INACTIVE)
                .update();
        districtCoverageAssembler.clearCache();

        DistrictCoverageSection activated = service.overallReport().districtCoverage();
        assertThat(activated.activeFacilityCountConsidered()).isEqualTo(ACTIVE_FACILITIES + 1);
        assertThat(district(activated, "KONAK").activeOsmFacilities())
                .isEqualTo(district(withInactive, "KONAK").activeOsmFacilities() + 1);
    }

    @Test
    void osmOccupancyRemainsUnavailableWhenDistrictCoverageRuns() {
        MunicipalQualityReport report = service.overallReport();

        assertThat(report.districtCoverage().status()).isEqualTo(DistrictCoverageStatus.AVAILABLE);
        assertThat(report.osm().occupancySnapshotCount()).isZero();
        assertThat(report.osm().nullAvailabilityCoverage().numerator())
                .isEqualTo(report.osm().activeFacilities());
        assertThat(count("""
                SELECT count(*) FROM municipal_occupancy_snapshots o
                JOIN municipal_data_sources s ON s.id = o.source_id
                WHERE s.source_key = 'osm-geofabrik-turkey'
                """)).isZero();
    }

    @Test
    void repeatedReportsDoNotMutateRegistryRows() {
        Map<String, Long> before = registryCounts();

        MunicipalQualityReport first = service.overallReport();
        MunicipalQualityReport second = service.overallReport();

        assertThat(registryCounts()).isEqualTo(before);
        assertThat(second.districtCoverage()).isEqualTo(first.districtCoverage());
    }

    @Test
    void facilityLimitExceededMarksDistrictCoverageUnavailable() {
        properties.getOps().getDistrictCoverage().setMaxFacilities(1);
        districtCoverageAssembler.clearCache();

        DistrictCoverageSection section = service.overallReport().districtCoverage();

        assertThat(section.status()).isEqualTo(DistrictCoverageStatus.UNAVAILABLE);
        assertThat(section.unavailableReason()).isEqualTo(MunicipalDistrictCoverageReason.FACILITY_LIMIT);
        assertThat(section.districtCount()).isZero();
        assertThat(section.activeFacilityCountConsidered()).isZero();
    }

    private static DistrictCoverageEntry district(DistrictCoverageSection section, String name) {
        return section.districts().stream()
                .filter(row -> name.equals(row.districtName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing district row " + name));
    }

    private Map<String, Long> registryCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : List.of(
                "municipal_parking_facilities",
                "municipal_facility_source_links",
                "municipal_facility_field_provenance",
                "municipal_occupancy_snapshots",
                "municipal_source_sync_runs",
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
        jdbc.sql("UPDATE municipal_data_sources SET last_successful_sync_at = NULL").update();
    }

    private static String labelMeta(String outcome) {
        return "{\"labelOutcome\":\"" + outcome + "\",\"district\":\"Konak\"}";
    }

    private void insertFacility(UUID id, String displayName, double lat, double lng, boolean active) {
        jdbc.sql("""
                INSERT INTO municipal_parking_facilities (
                    id,operator_name,facility_type,access_classification,display_name,address_text,
                    latitude,longitude,location,capacity_total,active,lifecycle_state,created_at,updated_at)
                VALUES (:id,'IZELMAN','OFF_STREET','PUBLIC',:name,'Izmir',
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
                    'CURRENT','CURRENT','district-coverage-it',now(),0)
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
                .param("correlation", "dc-it-" + sourceKey + "-" + startedAt.toEpochMilli())
                .param("startedAt", Timestamp.from(startedAt))
                .param("completedAt", Timestamp.from(startedAt.plusSeconds(5)))
                .param("status", status)
                .param("category", category)
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

    private UUID latestRunId(String sourceKey) {
        return jdbc.sql("""
                SELECT r.id FROM municipal_source_sync_runs r
                JOIN municipal_data_sources s ON s.id = r.source_id
                WHERE s.source_key = :sourceKey
                ORDER BY r.started_at DESC LIMIT 1
                """)
                .param("sourceKey", sourceKey)
                .query(UUID.class)
                .single();
    }
}
