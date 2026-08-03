package com.parkio.parking.infrastructure.persistence.municipal;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.MunicipalSourceHealthService;
import com.parkio.parking.application.quality.MunicipalQualityReport;
import com.parkio.parking.application.quality.MunicipalQualityReportService;
import com.parkio.parking.application.quality.SourceQualitySummary;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.MunicipalSourceOperatingMode;
import com.parkio.parking.externalsource.MunicipalSourceOperationalState;
import com.parkio.parking.infrastructure.osm.OsmGeofabrikSourceKeys;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
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
class MunicipalSourceModeSlaPostgresIT {
    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("parkio_municipal_mode_sla_it")
            .withUsername("parkio")
            .withPassword("parkio");

    @Autowired MunicipalSourceHealthService healthService;
    @Autowired MunicipalQualityReportService qualityReportService;
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
        registry.add("parkio.municipal.izum.enabled", () -> "true");
        registry.add("parkio.municipal.izum.scheduler-enabled", () -> "false");
        registry.add("parkio.municipal.osm.publication-enabled", () -> "true");
        registry.add("parkio.municipal.osm.import-enabled", () -> "false");
        registry.add("parkio.municipal.osm.scheduler-enabled", () -> "false");
        registry.add("parkio.municipal.osm.operating-mode", () -> "OPERATOR_IMPORTED");
        registry.add("parkio.municipal.izum.operating-mode", () -> "SCHEDULED");
        registry.add("parkio.municipal.ops.source-mode-sla-enabled", () -> "true");
        registry.add("parkio.municipal.ops.quality-report-enabled", () -> "true");
        registry.add("parkio.municipal.sla.critical-seconds-since-success", () -> "1800");
        registry.add("parkio.municipal.sla.warning-seconds-since-success", () -> "600");
    }

    @Autowired
    com.parkio.parking.infrastructure.config.MunicipalSourceProperties properties;

    @Test
    void oldSuccessfulOsmImportIsHealthyUnderModeAwareSla() {
        assertThat(properties.getOps().isSourceModeSlaEnabled()).isTrue();
        assertThat(properties.getOsm().getOperatingMode())
                .isEqualTo(MunicipalSourceOperatingMode.OPERATOR_IMPORTED);

        UUID osmId = sourceId(OsmGeofabrikSourceKeys.SOURCE_KEY);
        clearRuns(osmId);
        Instant success = Instant.now().minusSeconds(7200);
        insertCompleted(osmId, "SUCCESS", null, success);
        jdbc.update(
                "UPDATE municipal_data_sources SET last_successful_sync_at = ? WHERE id = ?",
                Timestamp.from(success),
                osmId);

        MunicipalSourceHealthService.Snapshot snapshot = healthService.snapshot(
                OsmGeofabrikSourceKeys.SOURCE_KEY, true, false);
        assertThat(snapshot.operatingMode()).isEqualTo(MunicipalSourceOperatingMode.OPERATOR_IMPORTED);
        assertThat(snapshot.consecutiveFailures()).isZero();
        assertThat(snapshot.secondsSinceSuccess()).isGreaterThanOrEqualTo(7200);
        assertThat(snapshot.operationalState()).isEqualTo(MunicipalSourceOperationalState.HEALTHY);

        MunicipalQualityReport report = qualityReportService.overallReport();
        SourceQualitySummary osm = report.sources().stream()
                .filter(s -> MunicipalSourceIdentity.OSM.equals(s.sourceKey()))
                .findFirst()
                .orElseThrow();
        assertThat(osm.sourceMode()).isEqualTo("OPERATOR_IMPORTED");
        assertThat(osm.operationalState()).isEqualTo("HEALTHY");
        assertThat(osm.secondsSinceSuccess()).isGreaterThanOrEqualTo(7200);
        assertThat(osm.occupancyFreshness()).isEqualTo("UNAVAILABLE");
        assertThat(report.osm().occupancySnapshotCount()).isZero();
        assertThat(report.osm().nullAvailabilityCoverage().numerator())
                .isEqualTo(report.osm().activeFacilities());
        assertThat(report.osm().nullAvailabilityCoverage().percentage())
                .isEqualTo(report.osm().activeFacilities() == 0 ? null : 100.0);

        // Read-only: second report does not mutate sync runs.
        long runsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM municipal_source_sync_runs WHERE source_id = ?",
                Long.class,
                osmId);
        qualityReportService.overallReport();
        qualityReportService.sourceReport(OsmGeofabrikSourceKeys.SOURCE_KEY, 10);
        long runsAfter = jdbc.queryForObject(
                "SELECT count(*) FROM municipal_source_sync_runs WHERE source_id = ?",
                Long.class,
                osmId);
        assertThat(runsAfter).isEqualTo(runsBefore);
    }

    @Test
    void repeatedFailedOsmImportsRemainCritical() {
        UUID osmId = sourceId(OsmGeofabrikSourceKeys.SOURCE_KEY);
        clearRuns(osmId);
        Instant t0 = Instant.now().minusSeconds(600);
        for (int i = 0; i < 5; i++) {
            insertCompleted(osmId, "FAILED", "read_timeout", t0.plusSeconds(i * 10L));
        }

        MunicipalSourceHealthService.Snapshot snapshot = healthService.snapshot(
                OsmGeofabrikSourceKeys.SOURCE_KEY, true, false);
        assertThat(snapshot.operationalState()).isEqualTo(MunicipalSourceOperationalState.CRITICAL);
        assertThat(snapshot.consecutiveFailures()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void izumOldSuccessRemainsCriticalUnderScheduledSemantics() {
        UUID izumId = sourceId("izmir-izum-otoparklar");
        clearRuns(izumId);
        Instant success = Instant.now().minusSeconds(7200);
        insertCompleted(izumId, "SUCCESS", null, success);
        jdbc.update(
                "UPDATE municipal_data_sources SET last_successful_sync_at = ? WHERE id = ?",
                Timestamp.from(success),
                izumId);

        MunicipalSourceHealthService.Snapshot snapshot = healthService.izumSnapshot();
        assertThat(snapshot.operatingMode()).isEqualTo(MunicipalSourceOperatingMode.SCHEDULED);
        assertThat(snapshot.operationalState()).isEqualTo(MunicipalSourceOperationalState.CRITICAL);
    }

    private UUID sourceId(String sourceKey) {
        return jdbc.queryForObject(
                "SELECT id FROM municipal_data_sources WHERE source_key = ?",
                UUID.class,
                sourceKey);
    }

    private void clearRuns(UUID sourceId) {
        jdbc.update("DELETE FROM municipal_source_sync_runs WHERE source_id = ?", sourceId);
    }

    private void insertCompleted(UUID sourceId, String status, String category, Instant at) {
        jdbc.update("""
                INSERT INTO municipal_source_sync_runs
                    (id,source_id,correlation_id,started_at,completed_at,status,error_category,
                     records_received,records_accepted,records_rejected,records_inserted,
                     records_updated,records_unchanged,occupancy_inserted)
                VALUES (?,?,?,?,?,?,?,0,0,0,0,0,0,0)
                """,
                UUID.randomUUID(),
                sourceId,
                "mode-sla-" + UUID.randomUUID(),
                Timestamp.from(at),
                Timestamp.from(at),
                status,
                category);
    }
}
