package com.parkio.parking.infrastructure.ispark;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.MunicipalFacilityQueryService;
import com.parkio.parking.application.MunicipalFacilitySyncService;
import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import com.parkio.parking.externalsource.provider.ParkingProviderCatalog;
import com.parkio.parking.infrastructure.izum.IzumMunicipalParkingAdapter;
import com.parkio.parking.testsupport.PostgisTestImages;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
class IsparkMunicipalSyncIntegrationTest {
    private static final DockerImageName POSTGIS_IMAGE = PostgisTestImages.dockerImageName();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("parkio_ispark_sync_it")
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
        registry.add("parkio.municipal.ispark.enabled", () -> "true");
        registry.add("parkio.municipal.ispark.max-retries", () -> "0");
        registry.add("parkio.municipal.ispark.base-url",
                () -> "http://localhost:" + SERVER.getAddress().getPort());
        registry.add("parkio.municipal.izum.enabled", () -> "false");
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Test
    void syncPublishesIstanbulNearbyWithCanonicalLabelAndIsolatesFromIzum() throws Exception {
        RESPONSE_BODY.set(fixture("/fixtures/municipal/ispark/park-sample.json"));
        RESPONSE_STATUS.set(200);

        var first = sync.sync(IsparkMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(first.status()).isIn(MunicipalSyncRunStatus.SUCCESS, MunicipalSyncRunStatus.PARTIAL_SUCCESS);
        assertThat(first.recordsAccepted()).isEqualTo(4);
        assertThat(first.occupancyInserted()).isPositive();
        assertThat(first.activeLinkCount()).isEqualTo(4);

        var nearby = query.nearby(41.0, 29.0, 50_000, 20);
        assertThat(nearby).isNotEmpty();
        assertThat(nearby).allSatisfy(view -> {
            assertThat(view.sourceLabel()).isEqualTo(ParkingProviderCatalog.ISPARK_DISPLAY_NAME);
            assertThat(view.sourceLabel()).doesNotContain("istanbul-ispark");
            assertThat(view.attribution()).contains("ISPARK");
        });
        var live = nearby.stream()
                .filter(v -> v.availableSpaces() != null)
                .findFirst()
                .orElseThrow();
        assertThat(live.freshness()).isEqualTo(MunicipalOccupancyFreshness.LIVE);
        assertThat(live.availabilitySource()).isEqualTo(IsparkMunicipalParkingAdapter.SOURCE_KEY);

        var zeroAvail = nearby.stream()
                .filter(v -> Integer.valueOf(0).equals(v.availableSpaces()))
                .findFirst();
        assertThat(zeroAvail).isPresent();

        long izumLinks = jdbc.queryForObject(
                """
                SELECT count(*) FROM municipal_facility_source_links l
                JOIN municipal_data_sources d ON d.id=l.source_id
                WHERE d.source_key=?
                """,
                Long.class,
                IzumMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(izumLinks).isZero();

        RESPONSE_BODY.set(fixture("/fixtures/municipal/ispark/park-shrunk.json"));
        var shrunk = sync.sync(IsparkMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(shrunk.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        assertThat(shrunk.recordsAccepted()).isEqualTo(2);
        assertThat(shrunk.recordsDeactivated()).isEqualTo(2);
        assertThat(shrunk.activeLinkCount()).isEqualTo(2);

        RESPONSE_STATUS.set(500);
        var failed = sync.sync(IsparkMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(failed.status()).isEqualTo(MunicipalSyncRunStatus.FAILED);
        assertThat(failed.recordsDeactivated()).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM municipal_facility_source_links l
                JOIN municipal_data_sources d ON d.id=l.source_id
                WHERE d.source_key=? AND l.active=true
                """,
                Long.class,
                IsparkMunicipalParkingAdapter.SOURCE_KEY)).isEqualTo(2);

        RESPONSE_STATUS.set(200);
        RESPONSE_BODY.set("[]".getBytes(StandardCharsets.UTF_8));
        var empty = sync.sync(IsparkMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(empty.recordsDeactivated()).isZero();

        RESPONSE_BODY.set(fixture("/fixtures/municipal/ispark/park-sample.json"));
        var restored = sync.sync(IsparkMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(restored.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        assertThat(restored.recordsReactivated()).isGreaterThanOrEqualTo(2);
        assertThat(restored.activeLinkCount()).isEqualTo(4);
        assertThat(facilities.count()).isGreaterThanOrEqualTo(4);
        assertThat(snapshots.count()).isPositive();
    }

    private static byte[] fixture(String path) throws IOException {
        try (var in = IsparkMunicipalSyncIntegrationTest.class.getResourceAsStream(path)) {
            return in.readAllBytes();
        }
    }

    private static HttpServer startServer() {
        try {
            RESPONSE_BODY.set("[]".getBytes(StandardCharsets.UTF_8));
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/ispark/Park", exchange -> {
                byte[] body = RESPONSE_BODY.get();
                int status = RESPONSE_STATUS.get();
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
