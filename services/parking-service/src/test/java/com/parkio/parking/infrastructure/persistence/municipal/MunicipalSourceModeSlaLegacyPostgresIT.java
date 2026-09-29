package com.parkio.parking.infrastructure.persistence.municipal;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.MunicipalSourceHealthService;
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
import com.parkio.parking.testsupport.PostgisTestImages;
import org.testcontainers.utility.DockerImageName;

/**
 * Legacy flag=false restores age-only CRITICAL for operator-imported OSM (DATA-WP-16 kill-switch).
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class MunicipalSourceModeSlaLegacyPostgresIT {
    private static final DockerImageName POSTGIS_IMAGE = PostgisTestImages.dockerImageName();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("parkio_municipal_mode_sla_legacy_it")
            .withUsername("parkio")
            .withPassword("parkio");

    @Autowired MunicipalSourceHealthService healthService;
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
        registry.add("parkio.municipal.osm.publication-enabled", () -> "true");
        registry.add("parkio.municipal.ops.source-mode-sla-enabled", () -> "false");
        registry.add("parkio.municipal.sla.critical-seconds-since-success", () -> "1800");
    }

    @Test
    void legacyFlagRestoresAgeOnlyCriticalForOsm() {
        UUID osmId = jdbc.queryForObject(
                "SELECT id FROM municipal_data_sources WHERE source_key = ?",
                UUID.class,
                OsmGeofabrikSourceKeys.SOURCE_KEY);
        Instant success = Instant.now().minusSeconds(7200);
        jdbc.update("""
                INSERT INTO municipal_source_sync_runs
                    (id,source_id,correlation_id,started_at,completed_at,status,error_category,
                     records_received,records_accepted,records_rejected,records_inserted,
                     records_updated,records_unchanged,occupancy_inserted)
                VALUES (?,?,?,?,?,'SUCCESS',null,0,0,0,0,0,0,0)
                """,
                UUID.randomUUID(),
                osmId,
                "legacy-age-" + UUID.randomUUID(),
                Timestamp.from(success),
                Timestamp.from(success));
        jdbc.update(
                "UPDATE municipal_data_sources SET last_successful_sync_at = ? WHERE id = ?",
                Timestamp.from(success),
                osmId);

        MunicipalSourceHealthService.Snapshot snapshot = healthService.snapshot(
                OsmGeofabrikSourceKeys.SOURCE_KEY, true, false);
        assertThat(snapshot.operationalState()).isEqualTo(MunicipalSourceOperationalState.CRITICAL);
    }
}
