package com.parkio.parking.infrastructure.persistence.municipal;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.IzelmanImportApplicationService;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import com.parkio.parking.externalsource.izelman.IzelmanSourceKeys;
import com.parkio.parking.externalsource.izelman.SourceAgeClassification;
import com.parkio.parking.externalsource.izelman.TariffCurrentness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
class IzelmanImportPostgresIT {
    private static final DockerImageName POSTGIS =
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS)
            .withDatabaseName("parkio_izelman_it")
            .withUsername("parkio")
            .withPassword("parkio");

    static final Path INPUT_DIR = createInputDir();

    @Autowired IzelmanImportApplicationService importService;
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
        registry.add("parkio.municipal.izelman.enabled", () -> "true");
        registry.add("parkio.municipal.izelman.facility-import-enabled", () -> "true");
        registry.add("parkio.municipal.izelman.roadside-import-enabled", () -> "true");
        registry.add("parkio.municipal.izelman.tariff-import-enabled", () -> "true");
        registry.add("parkio.municipal.izelman.facility-publication-enabled", () -> "false");
        registry.add("parkio.municipal.izelman.roadside-publication-enabled", () -> "false");
        registry.add("parkio.municipal.izelman.tariff-publication-enabled", () -> "false");
        registry.add("parkio.municipal.izelman.allowed-input-dir", () -> INPUT_DIR.toString());
    }

    @BeforeEach
    void cleanIzelmanRows() {
        jdbc.update("DELETE FROM municipal_tariff_rate_bands");
        jdbc.update("DELETE FROM municipal_tariff_assignments");
        jdbc.update("DELETE FROM municipal_tariff_plans");
        jdbc.update("DELETE FROM municipal_roadside_source_links");
        jdbc.update("DELETE FROM municipal_roadside_segments");
        jdbc.update("DELETE FROM municipal_izelman_import_runs");
        jdbc.update("DELETE FROM municipal_facility_source_links WHERE source_id IN "
                + "(SELECT id FROM municipal_data_sources WHERE family_key='izelman')");
        jdbc.update("DELETE FROM municipal_source_sync_runs WHERE source_id IN "
                + "(SELECT id FROM municipal_data_sources WHERE family_key='izelman')");
        jdbc.update("DELETE FROM municipal_parking_facilities WHERE primary_source_key LIKE 'izelman-%'");
    }

    @Test
    void facilityImportIsIdempotentAndWritesNoOccupancy() throws Exception {
        var first = importService.importConfigured(IzelmanSourceKeys.OPEN, false);
        assertThat(first.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        assertThat(first.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        assertThat(first.accepted()).isEqualTo(1);
        assertThat(first.rejected()).isEqualTo(1);
        assertThat(first.ageClassification()).isEqualTo(SourceAgeClassification.HISTORICAL);

        var second = importService.importConfigured(IzelmanSourceKeys.OPEN, false);
        assertThat(second.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        assertThat(second.unchanged() + second.updated() + second.inserted()).isEqualTo(1);

        Integer facilities = jdbc.queryForObject(
                "SELECT count(*) FROM municipal_parking_facilities WHERE primary_source_key=?",
                Integer.class, IzelmanSourceKeys.OPEN);
        Integer snapshots = jdbc.queryForObject("SELECT count(*) FROM municipal_occupancy_snapshots", Integer.class);
        assertThat(facilities).isEqualTo(1);
        assertThat(snapshots).isZero();
    }

    @Test
    void roadsideAndTariffImportPreserveUnknownSemantics() {
        var roadside = importService.importConfigured(IzelmanSourceKeys.ROADSIDE, false);
        assertThat(roadside.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        assertThat(roadside.accepted()).isGreaterThanOrEqualTo(1);

        Integer segments = jdbc.queryForObject("SELECT count(*) FROM municipal_roadside_segments", Integer.class);
        assertThat(segments).isEqualTo(2);

        var tariffs = importService.importConfigured(IzelmanSourceKeys.TARIFFS, false);
        assertThat(tariffs.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        Integer current = jdbc.queryForObject(
                "SELECT count(*) FROM municipal_tariff_plans WHERE currentness=?",
                Integer.class, TariffCurrentness.CURRENT.name());
        assertThat(current).isZero();
        Integer bands = jdbc.queryForObject("SELECT count(*) FROM municipal_tariff_rate_bands", Integer.class);
        assertThat(bands).isGreaterThan(0);
    }

    @Test
    void concurrentImportIsSkipped() throws Exception {
        UUID sourceId = jdbc.queryForObject(
                "SELECT id FROM municipal_data_sources WHERE source_key=?",
                UUID.class, IzelmanSourceKeys.OPEN);
        jdbc.update("INSERT INTO municipal_source_sync_runs(id,source_id,correlation_id,started_at,status,"
                        + "records_received,records_accepted,records_rejected,records_inserted,records_updated,"
                        + "records_unchanged,occupancy_inserted) VALUES(?,?,?,now(),'RUNNING',0,0,0,0,0,0,0)",
                UUID.randomUUID(), sourceId, "lock-" + UUID.randomUUID());
        var skipped = importService.importConfigured(IzelmanSourceKeys.OPEN, false);
        assertThat(skipped.status()).isEqualTo(MunicipalSyncRunStatus.SKIPPED);
    }

    private static Path createInputDir() {
        try {
            Path dir = Files.createTempDirectory("izelman-it-");
            Path fixtures = Path.of("src/test/resources/fixtures/municipal/izelman").toAbsolutePath();
            if (!Files.isDirectory(fixtures)) {
                fixtures = Path.of("services/parking-service/src/test/resources/fixtures/municipal/izelman")
                        .toAbsolutePath();
            }
            for (String key : IzelmanSourceKeys.ALL) {
                Files.copy(fixtures.resolve(key + ".csv"), dir.resolve(key + ".csv"));
            }
            return dir;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
