package com.parkio.parking.infrastructure.izum;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.MunicipalFacilityQueryService;
import com.parkio.parking.application.MunicipalFacilitySyncService;
import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
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
class IzumMunicipalSyncIntegrationTest {
    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("parkio_municipal_sync_it")
            .withUsername("parkio")
            .withPassword("parkio");

    static final AtomicReference<byte[]> RESPONSE_BODY = new AtomicReference<>();
    static final AtomicInteger RESPONSE_STATUS = new AtomicInteger(200);
    static final HttpServer SERVER = startServer();

    @Autowired MunicipalFacilitySyncService sync;
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
        registry.add("parkio.municipal.izum.enabled", () -> "true");
        registry.add("parkio.municipal.izum.max-retries", () -> "0");
        registry.add("parkio.municipal.izum.base-url",
                () -> "http://localhost:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Test
    void syncIsIdempotentPreservesFacilitiesHandlesStaleContractFailureAndRecovery() throws Exception {
        RESPONSE_BODY.set(fixture("/fixtures/municipal/izum/otoparklar-sample.json"));
        RESPONSE_STATUS.set(200);

        var first = sync.sync(IzumMunicipalParkingAdapter.SOURCE_KEY);
        long facilityCount = facilities.count();
        long occupancyCount = snapshots.count();
        long linkCount = jdbc.queryForObject(
                "SELECT count(*) FROM municipal_facility_source_links", Long.class);

        assertThat(first.status()).isIn(MunicipalSyncRunStatus.SUCCESS, MunicipalSyncRunStatus.PARTIAL_SUCCESS);
        assertThat(facilityCount).isPositive();
        assertThat(occupancyCount).isPositive();
        assertThat(linkCount).isEqualTo(facilityCount);

        var second = sync.sync(IzumMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(facilities.count()).isEqualTo(facilityCount);
        assertThat(second.recordsInserted()).isZero();
        // New fetch timestamps create new observation rows; inventory stays stable.
        assertThat(snapshots.count()).isGreaterThanOrEqualTo(occupancyCount);

        long beforeDedupe = snapshots.count();
        jdbc.update("""
                INSERT INTO municipal_occupancy_snapshots
                    (id,facility_id,source_id,source_link_id,sync_run_id,source_observed_at,fetched_at,
                     timestamp_provenance,capacity_total,occupied_spaces,available_spaces,
                     occupancy_status,raw_record_hash,created_at)
                SELECT gen_random_uuid(), facility_id, source_id, source_link_id, sync_run_id, source_observed_at,
                       fetched_at, timestamp_provenance, capacity_total, occupied_spaces, available_spaces,
                       occupancy_status, raw_record_hash, created_at
                FROM municipal_occupancy_snapshots
                ORDER BY fetched_at DESC LIMIT 1
                ON CONFLICT ON CONSTRAINT uq_municipal_occupancy_snapshots_dedupe DO NOTHING
                """);
        assertThat(snapshots.count()).isEqualTo(beforeDedupe);

        UUID sourceId = jdbc.queryForObject(
                "SELECT id FROM municipal_data_sources WHERE source_key = ?",
                UUID.class, IzumMunicipalParkingAdapter.SOURCE_KEY);
        jdbc.update("""
                INSERT INTO municipal_source_sync_runs
                    (id,source_id,correlation_id,started_at,status,records_received,records_accepted,
                     records_rejected,records_inserted,records_updated,records_unchanged,occupancy_inserted)
                VALUES (?,?,?,now(),'RUNNING',0,0,0,0,0,0,0)
                """, UUID.randomUUID(), sourceId, "lock-test");
        var skipped = sync.sync(IzumMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(skipped.status()).isEqualTo(MunicipalSyncRunStatus.SKIPPED);
        jdbc.update("DELETE FROM municipal_source_sync_runs WHERE correlation_id = 'lock-test'");

        var live = query.nearby(38.43, 27.14, 10_000, 5).get(0);
        assertThat(live.freshness()).isEqualTo(MunicipalOccupancyFreshness.LIVE);
        assertThat(live.availableSpaces()).isNotNull();
        assertThat(live.attribution()).isNotBlank();

        jdbc.update("""
                DELETE FROM municipal_occupancy_snapshots a
                USING municipal_occupancy_snapshots b
                WHERE a.facility_id = b.facility_id
                  AND a.fetched_at < b.fetched_at
                """);
        jdbc.update("UPDATE municipal_occupancy_snapshots SET fetched_at = now() - interval '2 days'");
        var stale = query.findById(live.id()).orElseThrow();
        assertThat(stale.freshness()).isEqualTo(MunicipalOccupancyFreshness.STALE);
        assertThat(stale.availableSpaces()).isNull();
        assertThat(stale.capacityTotal()).isNotNull();

        RESPONSE_STATUS.set(500);
        var failed = sync.sync(IzumMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(failed.status()).isEqualTo(MunicipalSyncRunStatus.FAILED);
        assertThat(facilities.count()).isEqualTo(facilityCount);

        RESPONSE_STATUS.set(200);
        RESPONSE_BODY.set("[{}]".getBytes(StandardCharsets.UTF_8));
        var contractFailed = sync.sync(IzumMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(contractFailed.status()).isEqualTo(MunicipalSyncRunStatus.FAILED);
        assertThat(contractFailed.errorCategory()).isEqualTo("contract");
        assertThat(facilities.count()).isEqualTo(facilityCount);

        RESPONSE_BODY.set(fixture("/fixtures/municipal/izum/otoparklar-sample.json"));
        var recovered = sync.sync(IzumMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(recovered.status()).isIn(MunicipalSyncRunStatus.SUCCESS, MunicipalSyncRunStatus.PARTIAL_SUCCESS);
        assertThat(facilities.count()).isEqualTo(facilityCount);
        var afterRecovery = query.findById(live.id()).orElseThrow();
        assertThat(afterRecovery.freshness()).isEqualTo(MunicipalOccupancyFreshness.LIVE);
        assertThat(afterRecovery.availableSpaces()).isNotNull();
    }

    private static byte[] fixture(String path) throws IOException {
        try (var in = IzumMunicipalSyncIntegrationTest.class.getResourceAsStream(path)) {
            return in.readAllBytes();
        }
    }

    private static HttpServer startServer() {
        try {
            RESPONSE_BODY.set("[]".getBytes(StandardCharsets.UTF_8));
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/api/ibb/izum/otoparklar", exchange -> {
                byte[] body = RESPONSE_BODY.get();
                int status = RESPONSE_STATUS.get();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}