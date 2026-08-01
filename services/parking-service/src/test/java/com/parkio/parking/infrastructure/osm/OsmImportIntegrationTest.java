package com.parkio.parking.infrastructure.osm;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.MunicipalFacilityQueryService;
import com.parkio.parking.application.OsmImportApplicationService;
import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class OsmImportIntegrationTest {
    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("parkio_osm_import_it")
            .withUsername("parkio")
            .withPassword("parkio");

    static final Path FIXTURE_DIR;
    static final Path FIXTURE_PATH;

    static {
        try {
            FIXTURE_DIR = Files.createTempDirectory("parkio-osm-it");
            FIXTURE_PATH = FIXTURE_DIR.resolve("izmir-parking-sample.geojson");
            try (var in = OsmImportIntegrationTest.class.getResourceAsStream(
                    "/fixtures/municipal/osm/izmir-parking-sample.geojson")) {
                Files.write(FIXTURE_PATH, in.readAllBytes());
            }
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @Autowired OsmImportApplicationService importService;
    @Autowired MunicipalFacilityQueryService query;
    @Autowired MunicipalFacilityRepository facilities;
    @Autowired MunicipalOccupancySnapshotRepository snapshots;
    @Autowired JdbcTemplate jdbc;

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
        registry.add("parkio.municipal.osm.import-enabled", () -> "true");
        registry.add("parkio.municipal.osm.conflation-enabled", () -> "true");
        registry.add("parkio.municipal.osm.auto-match-enabled", () -> "false");
        registry.add("parkio.municipal.osm.publication-enabled", () -> "true");
        registry.add("parkio.municipal.osm.label-policy", () -> "osm-label-v1");
        registry.add("parkio.municipal.osm.local-input-path", () -> FIXTURE_PATH.toString());
        registry.add("parkio.municipal.osm.allowed-input-dir", () -> FIXTURE_DIR.toString());
        registry.add("parkio.municipal.osm.clip-version", () -> "izmir-admin-izbb-2024-10-18-v1");
        registry.add("parkio.municipal.registry.provenance-ingest-write-enabled", () -> "true");
        registry.add("parkio.municipal.registry.provenance-publication-enabled", () -> "true");
        registry.add("parkio.municipal.discovery.duplicate-presentation-enabled", () -> "true");
    }

    @Test
    void importIsIdempotentCreatesNoOccupancyAndAppliesReadableLabels() {
        var first = importService.importFromConfiguredPath(false);
        assertThat(first.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        assertThat(first.clipVersion()).isEqualTo("izmir-admin-izbb-2024-10-18-v1");
        // PUBLIC/UNKNOWN only: node/1001, relation/3003, way/1001, way/7007, way/8008
        assertThat(first.extracted()).isEqualTo(5);
        assertThat(snapshots.count()).isZero();
        long facilitiesAfterFirst = facilities.count();
        assertThat(facilitiesAfterFirst).isEqualTo(5);

        assertThat(jdbc.queryForObject(
                "SELECT display_name FROM municipal_parking_facilities f "
                        + "JOIN municipal_facility_source_links l ON l.facility_id=f.id "
                        + "WHERE l.external_id='node/1001'",
                String.class)).isEqualTo("Konak Test Otoparkı");
        assertThat(jdbc.queryForObject(
                "SELECT display_name FROM municipal_parking_facilities f "
                        + "JOIN municipal_facility_source_links l ON l.facility_id=f.id "
                        + "WHERE l.external_id='relation/3003'",
                String.class)).isEqualTo("Açık Otopark");
        assertThat(jdbc.queryForObject(
                "SELECT display_name FROM municipal_parking_facilities f "
                        + "JOIN municipal_facility_source_links l ON l.facility_id=f.id "
                        + "WHERE l.external_id='way/7007'",
                String.class)).isEqualTo("Bornova Belediyesi Otoparkı");
        assertThat(jdbc.queryForObject(
                "SELECT display_name FROM municipal_parking_facilities f "
                        + "JOIN municipal_facility_source_links l ON l.facility_id=f.id "
                        + "WHERE l.external_id='way/8008'",
                String.class)).isEqualTo("Parkio Otoparkı");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM municipal_parking_facilities WHERE display_name LIKE 'OSM parking %'",
                Long.class)).isZero();

        long provenanceAfterFirst = jdbc.queryForObject(
                "SELECT count(*) FROM municipal_facility_field_provenance", Long.class);
        assertThat(provenanceAfterFirst).isPositive();
        // NAME provenance only for real name-bearing tags (node/1001, way/1001)
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM municipal_facility_field_provenance p
                JOIN municipal_facility_source_links l ON l.facility_id=p.facility_id
                WHERE p.field_name='NAME' AND l.external_id IN ('node/1001','way/1001')
                """,
                Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM municipal_facility_field_provenance p
                JOIN municipal_facility_source_links l ON l.facility_id=p.facility_id
                WHERE p.field_name='NAME' AND l.external_id IN ('relation/3003','way/7007','way/8008')
                """,
                Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM municipal_occupancy_snapshots", Long.class)).isZero();

        var second = importService.importFromConfiguredPath(false);
        assertThat(facilities.count()).isEqualTo(facilitiesAfterFirst);
        assertThat(second.inserted()).isZero();
        assertThat(second.updated()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM municipal_facility_field_provenance", Long.class))
                .isEqualTo(provenanceAfterFirst);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM municipal_facility_source_links", Long.class)).isEqualTo(5);

        jdbc.update("""
                INSERT INTO municipal_source_sync_runs
                    (id, source_id, correlation_id, started_at, status, records_received, records_accepted,
                     records_rejected, records_inserted, records_updated, records_unchanged, occupancy_inserted)
                SELECT '33333333-3333-3333-3333-333333333301', id, 'lock', NOW(), 'RUNNING',0,0,0,0,0,0,0
                FROM municipal_data_sources WHERE source_key='osm-geofabrik-turkey'
                """);
        var skipped = importService.importFromConfiguredPath(false);
        assertThat(skipped.status()).isEqualTo(MunicipalSyncRunStatus.SKIPPED);
        jdbc.update("DELETE FROM municipal_source_sync_runs WHERE id='33333333-3333-3333-3333-333333333301'");

        var osmFacility = query.nearby(38.4187, 27.1283, 2000, 20).stream()
                .filter(f -> f.displayName().contains("Konak"))
                .findFirst();
        assertThat(osmFacility).isPresent();
        assertThat(osmFacility.get().availableSpaces()).isNull();
        assertThat(osmFacility.get().freshness()).isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
        assertThat(osmFacility.get().attribution()).contains("OpenStreetMap");
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM municipal_facility_field_provenance p
                JOIN municipal_facility_source_links l ON l.facility_id=p.facility_id
                WHERE l.external_id='node/1001' AND p.field_name='NAME'
                """,
                Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM municipal_link_candidates", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM municipal_occupancy_snapshots", Long.class)).isZero();
        assertThat(first.qualityReportJson()).contains("\"labelPolicyVersion\":\"osm-label-v1\"");
    }

    @Test
    void failedImportDoesNotMutateLabelsOrProvenance() throws Exception {
        var ok = importService.importFromConfiguredPath(false);
        assertThat(ok.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        String nameBefore = jdbc.queryForObject(
                "SELECT display_name FROM municipal_parking_facilities f "
                        + "JOIN municipal_facility_source_links l ON l.facility_id=f.id "
                        + "WHERE l.external_id='node/1001'",
                String.class);
        long provenanceBefore = jdbc.queryForObject(
                "SELECT count(*) FROM municipal_facility_field_provenance", Long.class);
        long facilitiesBefore = facilities.count();

        Path bad = FIXTURE_DIR.resolve("broken.geojson");
        Files.writeString(bad, "{ not-valid-geojson");
        var failed = importService.importPath(bad, false);
        assertThat(failed.status()).isEqualTo(MunicipalSyncRunStatus.FAILED);

        assertThat(facilities.count()).isEqualTo(facilitiesBefore);
        assertThat(jdbc.queryForObject(
                "SELECT display_name FROM municipal_parking_facilities f "
                        + "JOIN municipal_facility_source_links l ON l.facility_id=f.id "
                        + "WHERE l.external_id='node/1001'",
                String.class)).isEqualTo(nameBefore);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM municipal_facility_field_provenance", Long.class))
                .isEqualTo(provenanceBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM municipal_parking_facilities WHERE active=true", Long.class))
                .isEqualTo(5);
    }

    @Test
    void softDeactivationThenFullImportReactivatesFacilities() throws Exception {
        var first = importService.importFromConfiguredPath(false);
        assertThat(first.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        assertThat(facilities.count()).isEqualTo(5);

        Path reduced = FIXTURE_DIR.resolve("reduced.geojson");
        String raw = Files.readString(FIXTURE_PATH);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = mapper.readTree(raw);
        var features = root.withArray("features");
        var kept = mapper.createArrayNode();
        kept.add(features.get(0));
        ((com.fasterxml.jackson.databind.node.ObjectNode) root).set("features", kept);
        Files.writeString(reduced, mapper.writeValueAsString(root));

        var reducedResult = importService.importPath(reduced, false);
        assertThat(reducedResult.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        assertThat(reducedResult.deactivated()).isGreaterThan(0);
        Long activeAfterReduce = jdbc.queryForObject(
                "SELECT COUNT(*) FROM municipal_parking_facilities WHERE active=true", Long.class);
        assertThat(activeAfterReduce).isEqualTo(1);

        var restored = importService.importFromConfiguredPath(false);
        assertThat(restored.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        Long activeAfterRestore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM municipal_parking_facilities WHERE active=true", Long.class);
        assertThat(activeAfterRestore).isEqualTo(5);
    }
}
