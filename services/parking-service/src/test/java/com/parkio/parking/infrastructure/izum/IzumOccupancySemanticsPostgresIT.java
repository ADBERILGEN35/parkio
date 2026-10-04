package com.parkio.parking.infrastructure.izum;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.parkio.parking.application.MunicipalFacilityQueryService;
import com.parkio.parking.application.MunicipalFacilitySyncService;
import com.parkio.parking.application.port.MunicipalQualityReportQueryPort;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.testsupport.PostgisTestImages;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
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

/**
 * CL-F22 on PostGIS, through the real İZUM sync: (c) an unchanged feed keeps the time of the last run
 * in which it changed, and the quality report ages it from that time; (d) a car park reported closed or
 * without a free count is stored UNAVAILABLE, published without spaces, and not counted as exposed in
 * the quality report. The İZUM endpoint is a local stub serving the repository's synthetic fixture;
 * nothing leaves the machine.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class IzumOccupancySemanticsPostgresIT {
    private static final DockerImageName POSTGIS_IMAGE = PostgisTestImages.dockerImageName();
    private static final String LATEST_RUN = """
            FROM municipal_occupancy_snapshots
            WHERE fetched_at = (SELECT max(fetched_at) FROM municipal_occupancy_snapshots)
            """;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("parkio_izum_semantics_it")
            .withUsername("parkio")
            .withPassword("parkio");

    static final AtomicReference<byte[]> RESPONSE_BODY = new AtomicReference<>();
    static final HttpServer SERVER = startServer();

    @Autowired MunicipalFacilitySyncService sync;
    @Autowired MunicipalFacilityQueryService query;
    @Autowired JdbcTemplate jdbc;
    @Autowired MunicipalQualityReportQueryPort qualityReport;

    private final ObjectMapper mapper = new ObjectMapper();

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
    void unchangedFeedsAgeFromTheirLastChangeAndClosedOrCountLessCarParksAreUnavailable() throws Exception {
        byte[] sample = fixture("/fixtures/municipal/izum/otoparklar-sample.json");
        RESPONSE_BODY.set(sample);

        sync.sync(IzumMunicipalParkingAdapter.SOURCE_KEY);
        Instant firstRun = latestFetchedAt();
        long readings = count("SELECT count(*) " + LATEST_RUN);
        assertThat(readings).isPositive();
        assertThat(count("SELECT count(*) " + LATEST_RUN + " AND occupancy_status = 'LIVE'")).isEqualTo(readings);
        assertThat(count("SELECT count(*) " + LATEST_RUN + " AND source_observed_at IS NULL")).isEqualTo(readings);

        // Same records, same raw hashes: every reading keeps the first run's fetch time.
        sync.sync(IzumMunicipalParkingAdapter.SOURCE_KEY);
        Instant secondRun = latestFetchedAt();
        assertThat(secondRun).isAfter(firstRun);
        assertThat(count("SELECT count(*) " + LATEST_RUN + " AND source_observed_at = ?", Timestamp.from(firstRun)))
                .isEqualTo(readings);
        // The query layer adds that age to the transport age: the readings are as old as the first run.
        assertThat(count("SELECT count(*) " + LATEST_RUN + " AND fetched_at > source_observed_at")).isEqualTo(readings);
        // The quality report ages them from the first run too (#246 review N1): with the aging threshold
        // exactly at the second run's fetch, they are AGING, not LIVE.
        var frozen = qualityReport.countIzumFreshnessBuckets(10, 900, secondRun.plusSeconds(10));
        assertThat(frozen.live()).isZero();
        assertThat(frozen.aging()).isEqualTo(readings);

        // One record closes, another loses its free count: the feed moved, so every reading goes back
        // to the fetch time, and those two are stored UNAVAILABLE.
        ArrayNode changed = (ArrayNode) mapper.readTree(sample);
        ObjectNode closedRecord = (ObjectNode) changed.get(0);
        ObjectNode countLessRecord = (ObjectNode) changed.get(1);
        closedRecord.put("status", "Closed");
        ((ObjectNode) countLessRecord.get("occupancy").get("total")).putNull("free");
        RESPONSE_BODY.set(mapper.writeValueAsBytes(changed));

        sync.sync(IzumMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(count("SELECT count(*) " + LATEST_RUN + " AND source_observed_at IS NULL")).isEqualTo(readings);
        String closedUfid = closedRecord.get("ufid").asText();
        String countLessUfid = countLessRecord.get("ufid").asText();
        assertThat(statusOf(closedUfid)).isEqualTo("UNAVAILABLE");
        assertThat(statusOf(countLessUfid)).isEqualTo("UNAVAILABLE");
        assertThat(count("SELECT count(*) " + LATEST_RUN + " AND occupancy_status = 'LIVE'")).isEqualTo(readings - 2);

        var closed = query.findById(facilityOf(closedUfid)).orElseThrow();
        assertThat(closed.freshness()).isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
        assertThat(closed.availableSpaces()).isNull();
        var countLess = query.findById(facilityOf(countLessUfid)).orElseThrow();
        assertThat(countLess.freshness()).isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
        assertThat(countLess.availableSpaces()).isNull();
        var open = query.findById(facilityOf(changed.get(2).get("ufid").asText())).orElseThrow();
        assertThat(open.freshness()).isEqualTo(MunicipalOccupancyFreshness.LIVE);
        assertThat(open.availableSpaces()).isNotNull();

        // The operator quality report agrees with publication: the closed car park, which keeps its
        // counts, is not "availability exposed" (#246 review N1).
        var buckets = qualityReport.countIzumFreshnessBuckets(300, 900, Instant.now());
        assertThat(buckets.availabilityExposed()).isEqualTo(readings - 2);
    }

    private Instant latestFetchedAt() {
        return jdbc.queryForObject("SELECT max(fetched_at) FROM municipal_occupancy_snapshots", Timestamp.class)
                .toInstant();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private String statusOf(String ufid) {
        return jdbc.queryForObject("""
                SELECT o.occupancy_status
                FROM municipal_occupancy_snapshots o
                JOIN municipal_facility_source_links l ON l.id = o.source_link_id
                WHERE o.fetched_at = (SELECT max(fetched_at) FROM municipal_occupancy_snapshots)
                  AND l.external_id = ?
                """, String.class, ufid);
    }

    private UUID facilityOf(String ufid) {
        return jdbc.queryForObject("SELECT facility_id FROM municipal_facility_source_links WHERE external_id = ?",
                UUID.class, ufid);
    }

    private static byte[] fixture(String path) throws IOException {
        try (var in = IzumOccupancySemanticsPostgresIT.class.getResourceAsStream(path)) {
            return in.readAllBytes();
        }
    }

    private static HttpServer startServer() {
        try {
            RESPONSE_BODY.set("[]".getBytes(StandardCharsets.UTF_8));
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/api/ibb/izum/otoparklar", exchange -> {
                byte[] body = RESPONSE_BODY.get();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
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
