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
        registry.add("parkio.municipal.osm.local-input-path", () -> FIXTURE_PATH.toString());
        registry.add("parkio.municipal.osm.allowed-input-dir", () -> FIXTURE_DIR.toString());
    }

    @Test
    void importIsIdempotentCreatesNoOccupancyAndSkipsWhenLocked() {
        var first = importService.importFromConfiguredPath(false);
        assertThat(first.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        // PUBLIC/UNKNOWN only: node/1001, relation/3003, way/1001
        assertThat(first.extracted()).isEqualTo(3);
        assertThat(snapshots.count()).isZero();
        long facilitiesAfterFirst = facilities.count();
        assertThat(facilitiesAfterFirst).isEqualTo(3);

        var second = importService.importFromConfiguredPath(false);
        assertThat(facilities.count()).isEqualTo(facilitiesAfterFirst);
        assertThat(second.inserted()).isZero();

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
    }
}