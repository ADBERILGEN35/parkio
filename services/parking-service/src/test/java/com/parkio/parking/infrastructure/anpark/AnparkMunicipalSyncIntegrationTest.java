package com.parkio.parking.infrastructure.anpark;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.MunicipalFacilityQueryService;
import com.parkio.parking.application.MunicipalFacilitySyncService;
import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import com.parkio.parking.externalsource.provider.ParkingProviderCatalog;
import com.parkio.parking.infrastructure.ispark.IsparkMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.izum.IzumMunicipalParkingAdapter;
import com.parkio.parking.testsupport.PostgisTestImages;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
class AnparkMunicipalSyncIntegrationTest {
    private static final DockerImageName POSTGIS_IMAGE = PostgisTestImages.dockerImageName();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("parkio_anpark_sync_it")
            .withUsername("parkio")
            .withPassword("parkio");

    static final AtomicReference<byte[]> RESPONSE_BODY = new AtomicReference<>();
    static final AtomicInteger RESPONSE_STATUS = new AtomicInteger(200);
    static final AtomicInteger RESPONSE_DELAY_MS = new AtomicInteger(0);
    static final ExecutorService SERVER_EXECUTOR = Executors.newCachedThreadPool();
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
        registry.add("parkio.municipal.anpark.enabled", () -> "true");
        registry.add("parkio.municipal.anpark.max-retries", () -> "0");
        registry.add("parkio.municipal.anpark.base-url",
                () -> "http://localhost:" + SERVER.getAddress().getPort());
        registry.add("parkio.municipal.anpark.path", () -> "/wp-json/anpark/v1/parks");
        // Short timeouts so we can simulate transport timeouts quickly in this integration test.
        registry.add("parkio.municipal.anpark.connect-timeout", () -> "200ms");
        registry.add("parkio.municipal.anpark.read-timeout", () -> "200ms");
        registry.add("parkio.municipal.izum.enabled", () -> "false");
        registry.add("parkio.municipal.ispark.enabled", () -> "false");
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
        SERVER_EXECUTOR.shutdownNow();
    }

    @Test
    void syncPublishesAnkaraInventoryWithoutOccupancyAndIsolatesOtherProviders() throws Exception {
        RESPONSE_BODY.set(fixture("/fixtures/municipal/anpark/park-sample.json"));
        RESPONSE_STATUS.set(200);

        long snapshotsBefore = snapshots.count();
        var first = sync.sync(AnparkMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(first.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        // sample has 4 rows; 1 inactive filtered → 3 accepted; capacity=0 kept as unknown capacity
        assertThat(first.recordsAccepted()).isEqualTo(3);
        assertThat(first.occupancyInserted()).isZero();
        assertThat(first.activeLinkCount()).isEqualTo(3);
        assertThat(snapshots.count()).isEqualTo(snapshotsBefore);

        // active=true initial sync: inactive must not be active in DB, while active must be active.
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM municipal_facility_source_links l
                        JOIN municipal_data_sources d ON d.id=l.source_id AND d.source_key=?
                        WHERE l.external_id=? AND l.active=true
                        """,
                        Long.class,
                        AnparkMunicipalParkingAdapter.SOURCE_KEY,
                        "1095"))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM municipal_facility_source_links l
                        JOIN municipal_data_sources d ON d.id=l.source_id AND d.source_key=?
                        WHERE l.external_id=? AND l.active=true
                        """,
                        Long.class,
                        AnparkMunicipalParkingAdapter.SOURCE_KEY,
                        "1096"))
                .isEqualTo(0L);

        // capacity=0 handling must map to canonical unknown/null capacity (never availability/occupancy).
        assertThat(jdbc.queryForObject(
                        """
                        SELECT f.capacity_total
                        FROM municipal_parking_facilities f
                        JOIN municipal_facility_source_links l ON l.facility_id=f.id AND l.active=true
                        JOIN municipal_data_sources d ON d.id=l.source_id AND d.source_key=?
                        WHERE l.external_id=?
                        """,
                        Integer.class,
                        AnparkMunicipalParkingAdapter.SOURCE_KEY,
                        "1098"))
                .isNull();

        var nearby = query.nearby(39.93, 32.86, 50_000, 20);
        assertThat(nearby).isNotEmpty();
        assertThat(nearby).allSatisfy(view -> {
            assertThat(view.sourceLabel()).isEqualTo(ParkingProviderCatalog.ANPARK_DISPLAY_NAME);
            assertThat(view.sourceLabel()).doesNotContain("ankara-anpark");
            assertThat(view.sourceLabel()).doesNotContain("wp-json");
            assertThat(view.attribution()).contains("ANPARK");
            assertThat(view.availableSpaces()).isNull();
            assertThat(view.occupiedSpaces()).isNull();
            assertThat(view.freshness()).isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
        });

        long isparkLinks = jdbc.queryForObject(
                """
                SELECT count(*) FROM municipal_facility_source_links l
                JOIN municipal_data_sources d ON d.id=l.source_id
                WHERE d.source_key=?
                """,
                Long.class,
                IsparkMunicipalParkingAdapter.SOURCE_KEY);
        long izumLinks = jdbc.queryForObject(
                """
                SELECT count(*) FROM municipal_facility_source_links l
                JOIN municipal_data_sources d ON d.id=l.source_id
                WHERE d.source_key=?
                """,
                Long.class,
                IzumMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(isparkLinks).isZero();
        assertThat(izumLinks).isZero();

        // AUTHORITATIVE_FULL_SET edge case:
        // A non-empty structurally-valid authoritative feed where all facilities are active=false
        // must reconcile to an empty active set for ANPARK.
        RESPONSE_STATUS.set(200);
        RESPONSE_DELAY_MS.set(0);
        RESPONSE_BODY.set("""
                [
                  {"id":"1095","name":"ALSANCAK YOL BOYU OTOPARKI","type":"yolustu","district":"Altındağ",
                   "lat":39.941283987606035,"lng":32.855654018215056,"capacity":16,
                   "schedule":"Haftanın her günü 08:00-18:00","address":"Alsancak Mahallesi, Altındağ/Ankara","active":false},
                  {"id":"1098","name":"GÖKSU PARKI OTOPARKI","type":"rekreasyon","district":"Etimesgut",
                   "lat":39.988505,"lng":32.647731,"capacity":0,
                   "schedule":"Haftanın her günü 08:00-22:00","address":"Göksu Parkı, Etimesgut/Ankara","active":false},
                  {"id":"1118","name":"SIHHIYE ÇOK KATLI OTOPARKI","type":"kapali","district":"Çankaya",
                   "lat":39.926417,"lng":32.859245,"capacity":186,
                   "schedule":"Haftanın her günü 00:00-24:00","address":"Sıhhiye, Çankaya/Ankara","active":false}
                ]
                """.getBytes(StandardCharsets.UTF_8));

        var allInactive = sync.sync(AnparkMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(allInactive.occupancyInserted()).isZero();
        assertThat(allInactive.recordsAccepted()).isZero();
        assertThat(allInactive.recordsDeactivated()).isEqualTo(3);
        assertThat(allInactive.activeLinkCount()).isZero();
        assertThat(query.nearby(39.93, 32.86, 50_000, 20)).isEmpty();

        // Repeat same all-inactive snapshot: idempotent; no additional incorrect mutations.
        var allInactive2 = sync.sync(AnparkMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(allInactive2.recordsAccepted()).isZero();
        assertThat(allInactive2.recordsDeactivated()).isZero();
        assertThat(allInactive2.activeLinkCount()).isZero();

        // Later active=true records must reactivate.
        RESPONSE_BODY.set(fixture("/fixtures/municipal/anpark/park-sample.json"));
        RESPONSE_STATUS.set(200);
        RESPONSE_DELAY_MS.set(0);
        var restoredForEmptyActive = sync.sync(AnparkMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(restoredForEmptyActive.activeLinkCount()).isEqualTo(3);
        assertThat(restoredForEmptyActive.occupancyInserted()).isZero();
        assertThat(query.nearby(39.93, 32.86, 50_000, 20)).isNotEmpty();

        // All-invalid non-empty rows must not deactivate.
        RESPONSE_BODY.set("""
                [
                  {"id":"1095","name":"","type":"yolustu","district":"Altındağ",
                   "lat":39.941283987606035,"lng":32.855654018215056,"capacity":16,
                   "schedule":"Haftanın her günü 08:00-18:00","address":"Alsancak Mahallesi, Altındağ/Ankara","active":false},
                  {"id":"1098","name":"GÖKSU PARKI OTOPARKI","type":"rekreasyon","district":"Etimesgut",
                   "lat":0.0,"lng":32.647731,"capacity":0,
                   "schedule":"Haftanın her günü 08:00-22:00","address":"Göksu Parkı, Etimesgut/Ankara","active":false}
                ]
                """.getBytes(StandardCharsets.UTF_8));
        RESPONSE_STATUS.set(200);
        RESPONSE_DELAY_MS.set(0);
        var invalidAll = sync.sync(AnparkMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(invalidAll.recordsDeactivated()).isZero();
        assertThat(invalidAll.activeLinkCount()).isEqualTo(3);

        // Mixed valid+invalid non-empty must not deactivate.
        RESPONSE_BODY.set("""
                [
                  {"id":"1095","name":"ALSANCAK YOL BOYU OTOPARKI","type":"yolustu","district":"Altındağ",
                   "lat":39.941283987606035,"lng":32.855654018215056,"capacity":16,
                   "schedule":"Haftanın her günü 08:00-18:00","address":"Alsancak Mahallesi, Altındağ/Ankara","active":false},
                  {"id":"1118","name":"SIHHIYE ÇOK KATLI OTOPARKI","type":"kapali","district":"Çankaya",
                   "lat":0.0,"lng":32.859245,"capacity":186,
                   "schedule":"Haftanın her günü 00:00-24:00","address":"Sıhhiye, Çankaya/Ankara","active":false}
                ]
                """.getBytes(StandardCharsets.UTF_8));
        RESPONSE_STATUS.set(200);
        RESPONSE_DELAY_MS.set(0);
        var invalidMixed = sync.sync(AnparkMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(invalidMixed.recordsDeactivated()).isZero();
        assertThat(invalidMixed.activeLinkCount()).isEqualTo(3);

        // Timeout must not deactivate.
        RESPONSE_STATUS.set(200);
        RESPONSE_DELAY_MS.set(1000);
        var timeout = sync.sync(AnparkMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(timeout.status()).isEqualTo(MunicipalSyncRunStatus.FAILED);
        assertThat(timeout.recordsDeactivated()).isZero();
        // FAILED catch-path DTO reports activeLinkCount=0; DB must retain the prior active set.
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM municipal_facility_source_links l
                JOIN municipal_data_sources d ON d.id=l.source_id
                WHERE d.source_key=? AND l.active=true
                """,
                Long.class,
                AnparkMunicipalParkingAdapter.SOURCE_KEY)).isEqualTo(3);
        RESPONSE_DELAY_MS.set(0);

        RESPONSE_BODY.set(fixture("/fixtures/municipal/anpark/park-shrunk.json"));
        var shrunk = sync.sync(AnparkMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(shrunk.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        assertThat(shrunk.recordsAccepted()).isEqualTo(2);
        assertThat(shrunk.recordsDeactivated()).isEqualTo(1);
        assertThat(shrunk.activeLinkCount()).isEqualTo(2);
        assertThat(shrunk.occupancyInserted()).isZero();

        RESPONSE_STATUS.set(500);
        var failed = sync.sync(AnparkMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(failed.status()).isEqualTo(MunicipalSyncRunStatus.FAILED);
        assertThat(failed.recordsDeactivated()).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM municipal_facility_source_links l
                JOIN municipal_data_sources d ON d.id=l.source_id
                WHERE d.source_key=? AND l.active=true
                """,
                Long.class,
                AnparkMunicipalParkingAdapter.SOURCE_KEY)).isEqualTo(2);

        RESPONSE_STATUS.set(200);
        RESPONSE_BODY.set("[]".getBytes(StandardCharsets.UTF_8));
        var empty = sync.sync(AnparkMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(empty.recordsDeactivated()).isZero();

        // active=false on previously active id soft-deactivates via absence from filtered set
        RESPONSE_BODY.set("""
                [
                  {"id":"1095","name":"ALSANCAK YOL BOYU OTOPARKI","type":"yolustu","district":"Altındağ",
                   "lat":39.941283987606035,"lng":32.855654018215056,"capacity":16,
                   "schedule":"08-18","address":"Alsancak","active":false},
                  {"id":"1118","name":"SIHHIYE ÇOK KATLI OTOPARKI","type":"kapali","district":"Çankaya",
                   "lat":39.926417,"lng":32.859245,"capacity":186,
                   "schedule":"00-24","address":"Sıhhiye","active":true}
                ]
                """.getBytes(StandardCharsets.UTF_8));
        var inactiveFlip = sync.sync(AnparkMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(inactiveFlip.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        assertThat(inactiveFlip.recordsAccepted()).isEqualTo(1);
        assertThat(inactiveFlip.recordsDeactivated()).isEqualTo(1);
        assertThat(inactiveFlip.activeLinkCount()).isEqualTo(1);
        assertThat(inactiveFlip.occupancyInserted()).isZero();

        // same upstream ID becomes active=false while still present in feed:
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM municipal_facility_source_links l
                        JOIN municipal_data_sources d ON d.id=l.source_id AND d.source_key=?
                        WHERE l.external_id=? AND l.active=true
                        """,
                        Long.class,
                        AnparkMunicipalParkingAdapter.SOURCE_KEY,
                        "1095"))
                .isEqualTo(0L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM municipal_facility_source_links l
                        JOIN municipal_data_sources d ON d.id=l.source_id AND d.source_key=?
                        WHERE l.external_id=? AND l.active=true
                        """,
                        Long.class,
                        AnparkMunicipalParkingAdapter.SOURCE_KEY,
                        "1118"))
                .isEqualTo(1L);

        // remains inactive on next full sync (still present upstream as active=false):
        RESPONSE_BODY.set("""
                [
                  {"id":"1095","name":"ALSANCAK YOL BOYU OTOPARKI","type":"yolustu","district":"Altındağ",
                   "lat":39.941283987606035,"lng":32.855654018215056,"capacity":16,
                   "schedule":"08-18","address":"Alsancak","active":false},
                  {"id":"1118","name":"SIHHIYE ÇOK KATLI OTOPARKI","type":"kapali","district":"Çankaya",
                   "lat":39.926417,"lng":32.859245,"capacity":186,
                   "schedule":"00-24","address":"Sıhhiye","active":true}
                ]
                """.getBytes(StandardCharsets.UTF_8));
        var staysInactive = sync.sync(AnparkMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(staysInactive.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        assertThat(staysInactive.recordsAccepted()).isEqualTo(1);
        assertThat(staysInactive.recordsDeactivated()).isZero();
        assertThat(staysInactive.activeLinkCount()).isEqualTo(1);
        assertThat(staysInactive.occupancyInserted()).isZero();

        // failed sync while inactive does not corrupt state (no accidental reactivation):
        RESPONSE_STATUS.set(500);
        var failedWhileInactive = sync.sync(AnparkMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(failedWhileInactive.status()).isEqualTo(MunicipalSyncRunStatus.FAILED);
        assertThat(failedWhileInactive.recordsDeactivated()).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM municipal_facility_source_links l
                JOIN municipal_data_sources d ON d.id=l.source_id
                WHERE d.source_key=? AND l.active=true
                """,
                Long.class,
                AnparkMunicipalParkingAdapter.SOURCE_KEY)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM municipal_facility_source_links l
                        JOIN municipal_data_sources d ON d.id=l.source_id AND d.source_key=?
                        WHERE l.external_id=? AND l.active=true
                        """,
                        Long.class,
                        AnparkMunicipalParkingAdapter.SOURCE_KEY,
                        "1095"))
                .isEqualTo(0L);

        // empty sync while inactive does not corrupt state:
        RESPONSE_STATUS.set(200);
        RESPONSE_BODY.set("[]".getBytes(StandardCharsets.UTF_8));
        var emptyWhileInactive = sync.sync(AnparkMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(emptyWhileInactive.recordsDeactivated()).isZero();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM municipal_facility_source_links l
                        JOIN municipal_data_sources d ON d.id=l.source_id AND d.source_key=?
                        WHERE l.active=true
                        """,
                        Long.class,
                        AnparkMunicipalParkingAdapter.SOURCE_KEY))
                .isEqualTo(1L);

        RESPONSE_BODY.set(fixture("/fixtures/municipal/anpark/park-sample.json"));
        var restored = sync.sync(AnparkMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(restored.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        assertThat(restored.recordsReactivated()).isGreaterThanOrEqualTo(1);
        assertThat(restored.activeLinkCount()).isEqualTo(3);
        assertThat(restored.occupancyInserted()).isZero();

        // inactive → active safely reactivates:
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM municipal_facility_source_links l
                        JOIN municipal_data_sources d ON d.id=l.source_id AND d.source_key=?
                        WHERE l.external_id=? AND l.active=true
                        """,
                        Long.class,
                        AnparkMunicipalParkingAdapter.SOURCE_KEY,
                        "1095"))
                .isEqualTo(1L);
        assertThat(facilities.count()).isGreaterThanOrEqualTo(3);
        assertThat(snapshots.count()).isEqualTo(snapshotsBefore);
    }

    private static byte[] fixture(String path) throws IOException {
        try (var in = AnparkMunicipalSyncIntegrationTest.class.getResourceAsStream(path)) {
            return in.readAllBytes();
        }
    }

    private static HttpServer startServer() {
        try {
            RESPONSE_BODY.set("[]".getBytes(StandardCharsets.UTF_8));
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(SERVER_EXECUTOR);
            server.createContext("/wp-json/anpark/v1/parks", exchange -> {
                byte[] body = RESPONSE_BODY.get();
                int status = RESPONSE_STATUS.get();
                int delayMs = RESPONSE_DELAY_MS.get();
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                exchange.getResponseHeaders().add("Content-Type", "application/json;charset=utf-8");
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
