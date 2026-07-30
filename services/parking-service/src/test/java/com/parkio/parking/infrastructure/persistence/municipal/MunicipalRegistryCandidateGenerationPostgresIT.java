package com.parkio.parking.infrastructure.persistence.municipal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.ConcurrentGenerationException;
import com.parkio.parking.application.LinkCandidateGenerationOrchestrator;
import com.parkio.parking.application.LinkCandidateGenerationService;
import com.parkio.parking.application.port.LinkCandidateGenerationRunPort;
import com.parkio.parking.externalsource.registry.LinkCandidatePolicy;
import com.parkio.parking.infrastructure.config.RegistryProperties;
import com.parkio.parking.infrastructure.metrics.RegistryMetrics;
import com.parkio.parking.infrastructure.persistence.LinkCandidateGenerationRunAdapter;
import com.parkio.parking.infrastructure.persistence.LinkCandidatePairDiscoveryAdapter;
import com.parkio.parking.infrastructure.persistence.RegistryPersistenceAdapter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class MunicipalRegistryCandidateGenerationPostgresIT {
    private static final DockerImageName POSTGIS =
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres");
    private static final UUID FACILITY_A = UUID.fromString("00000000-0000-0000-0000-000000005001");
    private static final UUID FACILITY_B = UUID.fromString("00000000-0000-0000-0000-000000005002");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS)
            .withDatabaseName("parkio_candidate_generation_it")
            .withUsername("parkio")
            .withPassword("parkio");

    private JdbcClient jdbc;
    private LinkCandidateGenerationRunAdapter runAdapter;
    private LinkCandidateGenerationOrchestrator orchestrator;

    @BeforeEach
    void reset() {
        Flyway flyway = flyway(null);
        flyway.clean();
        flyway.migrate();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        ObjectMapper mapper = new ObjectMapper();
        RegistryProperties properties = new RegistryProperties();
        properties.setCandidateGenerationEnabled(true);
        RegistryMetrics metrics = new RegistryMetrics(new SimpleMeterRegistry());
        runAdapter = new LinkCandidateGenerationRunAdapter(jdbc, mapper);
        var generation = new LinkCandidateGenerationService(
                properties, new RegistryPersistenceAdapter(jdbc), mapper, metrics, Clock.systemUTC());
        orchestrator = new LinkCandidateGenerationOrchestrator(
                properties, new LinkCandidatePairDiscoveryAdapter(jdbc), runAdapter,
                generation, mapper, metrics, Clock.systemUTC());
    }

    @Test
    void v32UpgradesToV33AndEnforcesOneRunningPair() {
        Flyway v32 = flyway(MigrationVersion.fromVersion("32"));
        v32.clean();
        v32.migrate();
        assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway(null).migrate().migrationsExecuted).isZero();
        assertThat(count("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_name='municipal_link_candidate_generation_runs'")).isEqualTo(1);

        var start = startRequest();
        assertThat(runAdapter.tryStart(start)).isPresent();
        assertThat(runAdapter.tryStart(start)).isEmpty();
    }

    @Test
    void boundedDryRunPersistAndIdempotentRerunDoNotMutateRegistryLinks() {
        insertFixtures();
        long aliases = count("SELECT count(*) FROM municipal_facility_aliases");
        long occupancy = count("SELECT count(*) FROM municipal_occupancy_snapshots");
        long tariffs = count("SELECT count(*) FROM municipal_tariff_assignments");

        var dry = orchestrator.generate(request(true, false));
        assertThat(dry.status()).isEqualTo("COMPLETED");
        assertThat(dry.aggregates().leftRecordsConsidered()).isEqualTo(1);
        assertThat(dry.aggregates().pairsConsidered()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM municipal_link_candidates")).isZero();

        var persisted = orchestrator.generate(request(false, true));
        assertThat(persisted.aggregates().candidatesPersisted()).isEqualTo(1);
        var repeated = orchestrator.generate(request(false, true));
        assertThat(repeated.aggregates().duplicatesSuppressed()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM municipal_link_candidates")).isEqualTo(1);

        assertThat(count("SELECT count(*) FROM municipal_facility_aliases")).isEqualTo(aliases);
        assertThat(count("SELECT count(*) FROM municipal_occupancy_snapshots")).isEqualTo(occupancy);
        assertThat(count("SELECT count(*) FROM municipal_tariff_assignments")).isEqualTo(tariffs);
        assertThat(count("SELECT count(*) FROM municipal_facility_source_links")).isEqualTo(2);
    }

    @Test
    void concurrentPairConflictsAndDisabledIzelmanPairIsRejected() {
        UUID active = runAdapter.tryStart(startRequest()).orElseThrow();
        assertThatThrownBy(() -> orchestrator.generate(request(false, false)))
                .isInstanceOf(ConcurrentGenerationException.class);
        runAdapter.complete(active, "FAILED",
                new LinkCandidateGenerationRunPort.Aggregates(0, 0, 0, 0, 0, java.util.Map.of(), 0, 1),
                "[]", "test", Instant.now(), 1);
        assertThat(runAdapter.tryStart(startRequest())).isPresent();

        assertThatThrownBy(() -> orchestrator.generate(new LinkCandidateGenerationOrchestrator.Request(
                "IZUM", "IZELMAN", null, null, null, null, true, false,
                List.of(), List.of(), null, "admin", "it")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("disabled");
    }

    private LinkCandidateGenerationOrchestrator.Request request(boolean dryRun, boolean persist) {
        return new LinkCandidateGenerationOrchestrator.Request(
                "IZUM", "OSM", 100d, 1, 1, 20, dryRun, persist,
                List.of(), List.of(), LinkCandidatePolicy.ALGORITHM_VERSION, "admin", "it");
    }

    private LinkCandidateGenerationRunPort.StartRequest startRequest() {
        return new LinkCandidateGenerationRunPort.StartRequest(
                "IZUM_OSM", LinkCandidatePolicy.ALGORITHM_VERSION, true, false,
                100, 100, 1000, 20, "{}", "admin", "it", Instant.now());
    }

    private void insertFixtures() {
        insertFacility(FACILITY_A, "Konak Otoparki", 38.4200, 27.1400);
        insertFacility(FACILITY_B, "Konak Otoparki", 38.4201, 27.1401);
        insertLink(FACILITY_A, "izmir-izum-otoparklar", "izum-1");
        insertLink(FACILITY_B, "osm-geofabrik-turkey", "osm-1");
    }

    private void insertFacility(UUID id, String name, double lat, double lng) {
        jdbc.sql("""
                INSERT INTO municipal_parking_facilities(
                  id,operator_name,facility_type,access_classification,display_name,address_text,
                  latitude,longitude,location,capacity_total,active,lifecycle_state,created_at,updated_at)
                VALUES (:id,'Izmir Belediyesi','OFF_STREET','PUBLIC',:name,'Ataturk 1',
                  :lat,:lng,ST_SetSRID(ST_MakePoint(:lng,:lat),4326)::geography,100,true,'ACTIVE',now(),now())
                """).param("id", id).param("name", name).param("lat", lat).param("lng", lng).update();
    }

    private void insertLink(UUID facilityId, String sourceKey, String externalId) {
        jdbc.sql("""
                INSERT INTO municipal_facility_source_links(
                  id,facility_id,source_id,external_id,source_name,source_metadata_json,raw_record_hash,
                  first_seen_at,last_seen_at,last_successful_sync_at,active,created_at,updated_at)
                SELECT :id,:facility,id,:external,:name,'{"district":"Konak"}',:hash,
                  now(),now(),now(),true,now(),now()
                FROM municipal_data_sources WHERE source_key=:sourceKey
                """).param("id", UUID.randomUUID()).param("facility", facilityId)
                .param("external", externalId).param("name", "Konak Otoparki")
                .param("hash", externalId + "-v1").param("sourceKey", sourceKey).update();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }
}
