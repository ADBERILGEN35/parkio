package com.parkio.parking.infrastructure.osm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.parkio.parking.application.MunicipalFacilityQueryService;
import com.parkio.parking.application.MunicipalFacilitySyncService;
import com.parkio.parking.application.OsmImportApplicationService;
import com.parkio.parking.application.OsmImportResult;
import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import com.parkio.parking.externalsource.osm.ConflationPolicy;
import com.parkio.parking.infrastructure.izum.IzumMunicipalParkingAdapter;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
 * Opt-in DATA-WP-02A real Izmir GeoJSON validation against disposable PostGIS.
 *
 * <p>Requires {@code PARKIO_OSM_REAL_IZMIR_VALIDATION=true} and
 * {@code PARKIO_OSM_REAL_IZMIR_GEOJSON}. Never part of default CI.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "PARKIO_OSM_REAL_IZMIR_VALIDATION", matches = "true")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class OsmRealIzmirImportValidationIT {
    private static final DockerImageName POSTGIS_IMAGE = PostgisTestImages.dockerImageName();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("parkio_osm_real_izmir_it")
            .withUsername("parkio")
            .withPassword("parkio");

    private static final AtomicReference<byte[]> IZUM_BODY = new AtomicReference<>();
    private static final HttpServer IZUM_SERVER = startServer();
    private static final Path GEOJSON = Path.of(
            requireEnv("PARKIO_OSM_REAL_IZMIR_GEOJSON")).toAbsolutePath().normalize();
    private static final Path REPORT_DIR = Path.of(
            System.getenv().getOrDefault(
                    "PARKIO_OSM_REAL_IZMIR_REPORT_DIR",
                    GEOJSON.getParent().toString())).toAbsolutePath().normalize();

    @Autowired OsmImportApplicationService importService;
    @Autowired MunicipalFacilitySyncService municipalSync;
    @Autowired MunicipalFacilityQueryService query;
    @Autowired MunicipalFacilityRepository facilities;
    @Autowired MunicipalOccupancySnapshotRepository snapshots;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    @AfterAll
    static void stopServer() {
        IZUM_SERVER.stop(0);
    }

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
        registry.add("parkio.municipal.manual-sync-enabled", () -> "true");
        registry.add("parkio.municipal.izum.enabled", () -> "true");
        registry.add("parkio.municipal.izum.max-retries", () -> "0");
        registry.add("parkio.municipal.izum.base-url",
                () -> "http://127.0.0.1:" + IZUM_SERVER.getAddress().getPort());
        registry.add("parkio.municipal.osm.import-enabled", () -> "true");
        registry.add("parkio.municipal.osm.conflation-enabled", () -> "true");
        registry.add("parkio.municipal.osm.auto-match-enabled", () -> "false");
        registry.add("parkio.municipal.osm.publication-enabled", () -> "true");
        registry.add("parkio.municipal.osm.local-input-path", () -> GEOJSON.toString());
        registry.add("parkio.municipal.osm.allowed-input-dir", () -> GEOJSON.getParent().toString());
        registry.add("parkio.municipal.osm.max-input-bytes", () -> "52428800");
    }

    @Test
    void realIzmirImportGate() throws Exception {
        assumeTrue(Files.isRegularFile(GEOJSON), "GeoJSON missing: " + GEOJSON);
        Files.createDirectories(REPORT_DIR);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("startedAt", Instant.now().toString());
        report.put("geojson", GEOJSON.getFileName().toString());
        report.put("geojsonBytes", Files.size(GEOJSON));
        report.put("policyVersion", ConflationPolicy.POLICY_VERSION);
        report.put("autoMatchEnabled", false);

        var municipal = municipalSync.sync(IzumMunicipalParkingAdapter.SOURCE_KEY);
        report.put("municipalSyncStatus", municipal.status().name());
        long municipalActive = countActive("izmir-izum-otoparklar");
        report.put("municipalActiveFacilities", municipalActive);
        assertThat(municipalActive).isPositive();
        long municipalOccupancy = snapshots.count();
        report.put("municipalOccupancySnapshotsBeforeOsm", municipalOccupancy);

        long t0 = System.nanoTime();
        OsmImportResult dry = importService.importFromConfiguredPath(true);
        report.put("dryRun", resultMap(dry, (System.nanoTime() - t0) / 1_000_000L));
        assertThat(dry.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        assertThat(dry.extracted()).isGreaterThan(100);
        assertThat(countActive("osm-geofabrik-turkey")).isZero();
        assertThat(snapshots.count()).isEqualTo(municipalOccupancy);

        t0 = System.nanoTime();
        OsmImportResult first = importService.importFromConfiguredPath(false);
        report.put("firstImport", resultMap(first, (System.nanoTime() - t0) / 1_000_000L));
        assertThat(first.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        assertThat(first.inserted()).isEqualTo(first.extracted());
        assertThat(countActive("osm-geofabrik-turkey")).isEqualTo(first.extracted());
        assertThat(countOsmOccupancy()).isZero();
        assertThat(snapshots.count()).isEqualTo(municipalOccupancy);

        t0 = System.nanoTime();
        OsmImportResult second = importService.importFromConfiguredPath(false);
        report.put("secondImport", resultMap(second, (System.nanoTime() - t0) / 1_000_000L));
        assertThat(second.inserted()).isZero();
        assertThat(second.unchanged()).isEqualTo(first.extracted());
        assertThat(countRows("osm-geofabrik-turkey")).isEqualTo(first.extracted());

        jdbc.update("""
                INSERT INTO municipal_source_sync_runs
                    (id, source_id, correlation_id, started_at, status, records_received, records_accepted,
                     records_rejected, records_inserted, records_updated, records_unchanged, occupancy_inserted)
                SELECT '44444444-4444-4444-4444-444444444401', id, 'lock', NOW(), 'RUNNING',0,0,0,0,0,0,0
                FROM municipal_data_sources WHERE source_key='osm-geofabrik-turkey'
                """);
        OsmImportResult skipped = importService.importFromConfiguredPath(false);
        assertThat(skipped.status()).isEqualTo(MunicipalSyncRunStatus.SKIPPED);
        jdbc.update("DELETE FROM municipal_source_sync_runs WHERE id='44444444-4444-4444-4444-444444444401'");
        report.put("concurrentSkipStatus", skipped.status().name());
        assertThat(countActive("osm-geofabrik-turkey")).isEqualTo(first.extracted());

        Path reduced = REPORT_DIR.resolve("izmir-parking-parkio-reduced.geojson");
        shrinkGeoJson(GEOJSON, reduced, 50);
        OsmImportResult reducedImport = importService.importPath(reduced, false);
        report.put("reducedCompleteImport", resultMap(reducedImport, null));
        assertThat(reducedImport.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        assertThat(reducedImport.deactivated()).isGreaterThan(0);
        assertThat(countActive("osm-geofabrik-turkey")).isEqualTo(reducedImport.extracted());

        OsmImportResult reactivate = importService.importFromConfiguredPath(false);
        report.put("reactivationImport", resultMap(reactivate, null));
        assertThat(countActive("osm-geofabrik-turkey")).isEqualTo(first.extracted());

        List<Map<String, Object>> autoProposals = jdbc.queryForList("""
                SELECT decision, total_score AS score, signal_values_json AS signals_json, external_id_a, external_id_b, decision_reason AS reason
                FROM municipal_facility_conflation_decisions
                WHERE decision = 'AUTO_MATCHED' AND superseded = false
                ORDER BY total_score DESC NULLS LAST
                LIMIT 250
                """);
        List<Map<String, Object>> reviewProposals = jdbc.queryForList("""
                SELECT decision, total_score AS score, signal_values_json AS signals_json, external_id_a, external_id_b, decision_reason AS reason
                FROM municipal_facility_conflation_decisions
                WHERE decision = 'REVIEW_REQUIRED' AND superseded = false
                ORDER BY total_score DESC NULLS LAST
                LIMIT 50
                """);
        List<Map<String, Object>> rejected = jdbc.queryForList("""
                SELECT decision, total_score AS score, signal_values_json AS signals_json, external_id_a, external_id_b, decision_reason AS reason
                FROM municipal_facility_conflation_decisions
                WHERE decision = 'REJECTED' AND superseded = false
                LIMIT 50
                """);
        report.put("autoMatchProposalCount", autoProposals.size());
        report.put("reviewSampleCount", reviewProposals.size());
        report.put("rejectedDecisionSampleCount", rejected.size());
        report.put("decisionTotals", jdbc.queryForList("""
                SELECT decision, COUNT(*) AS cnt
                FROM municipal_facility_conflation_decisions
                WHERE superseded = false
                GROUP BY decision
                ORDER BY decision
                """));
        report.put("autoMatchProposalsBounded", sanitizeDecisions(autoProposals));
        report.put("reviewProposalsBounded", sanitizeDecisions(reviewProposals));
        report.put("rejectedProposalsBounded", sanitizeDecisions(rejected));

        var nearby = query.nearby(38.432585, 27.14668, 3000, 50);
        report.put("nearbyCount", nearby.size());
        var osmNearby = nearby.stream()
                .filter(f -> f.sourceLabel() != null && f.sourceLabel().contains("OpenStreetMap"))
                .toList();
        boolean osmSeen = !osmNearby.isEmpty();
        boolean municipalLive = nearby.stream().anyMatch(f ->
                f.availableSpaces() != null && f.freshness() == MunicipalOccupancyFreshness.LIVE);
        boolean osmAvailabilityAlwaysNull = osmNearby.stream().allMatch(f -> f.availableSpaces() == null);
        long osmLiveOrAgingLeak = osmNearby.stream()
                .filter(f -> f.freshness() == MunicipalOccupancyFreshness.LIVE
                        || f.freshness() == MunicipalOccupancyFreshness.AGING)
                .count();
        report.put("discoverySeesOsmAttribution", osmSeen);
        report.put("discoverySeesMunicipalLive", municipalLive);
        report.put("osmAvailabilityAlwaysNull", osmAvailabilityAlwaysNull);
        report.put("osmLiveOrAgingLeakCount", osmLiveOrAgingLeak);
        report.put("osmNearbyFreshnessSample", osmNearby.stream().limit(20)
                .map(f -> f.freshness() + "|" + f.availableSpaces() + "|" + f.sourceLabel() + "|" + f.displayName())
                .toList());
        if (!nearby.isEmpty()) {
            var detail = query.findById(nearby.get(0).id());
            assertThat(detail).isPresent();
            report.put("detailAttribution", detail.get().attribution());
        }

        report.put("importRunRows", jdbc.queryForObject(
                "SELECT COUNT(*) FROM municipal_osm_import_runs", Long.class));
        report.put("autoMatchProposalDetail", autoProposals.isEmpty() ? null : autoProposals.get(0));
        report.put("finishedAt", Instant.now().toString());

        Path out = REPORT_DIR.resolve("data-wp-02a-controlled-import-report.json");
        Files.writeString(out, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8);

        assertThat(osmSeen).isTrue();
        assertThat(municipalLive).isTrue();
        assertThat(osmAvailabilityAlwaysNull).isTrue();
        assertThat(osmLiveOrAgingLeak).isZero();
        assertThat(first.extracted()).isGreaterThan(500);
    }

    private long countActive(String sourceKey) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM municipal_parking_facilities f
                JOIN municipal_facility_source_links l ON l.facility_id=f.id
                JOIN municipal_data_sources d ON d.id=l.source_id
                WHERE d.source_key=? AND f.active=true AND l.active=true
                """, Long.class, sourceKey);
        return n == null ? 0 : n;
    }

    private long countRows(String sourceKey) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM municipal_facility_source_links l
                JOIN municipal_data_sources d ON d.id=l.source_id
                WHERE d.source_key=?
                """, Long.class, sourceKey);
        return n == null ? 0 : n;
    }

    private long countOsmOccupancy() {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM municipal_occupancy_snapshots s
                WHERE s.facility_id IN (
                  SELECT l.facility_id FROM municipal_facility_source_links l
                  JOIN municipal_data_sources d ON d.id=l.source_id
                  WHERE d.source_key='osm-geofabrik-turkey'
                )
                """, Long.class);
        return n == null ? 0 : n;
    }

    private static Map<String, Object> resultMap(OsmImportResult r, Long durationMs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.status().name());
        m.put("dryRun", r.dryRun());
        m.put("sha256", r.sha256());
        m.put("clipVersion", r.clipVersion());
        m.put("elementsRead", r.elementsRead());
        m.put("extracted", r.extracted());
        m.put("rejected", r.rejected());
        m.put("inserted", r.inserted());
        m.put("updated", r.updated());
        m.put("unchanged", r.unchanged());
        m.put("deactivated", r.deactivated());
        m.put("reactivated", r.reactivated());
        m.put("conflationCandidates", r.conflationCandidates());
        m.put("autoMatched", r.autoMatched());
        m.put("reviewRequired", r.reviewRequired());
        m.put("rejectedMatches", r.rejectedMatches());
        m.put("hardConflicts", r.hardConflicts());
        m.put("qualityReportJson", r.qualityReportJson());
        m.put("errorCategory", r.errorCategory());
        if (durationMs != null) {
            m.put("durationMs", durationMs);
        }
        return m;
    }

    private static List<Map<String, Object>> sanitizeDecisions(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put("decision", row.get("decision"));
            copy.put("score", row.get("score"));
            copy.put("reason", row.get("reason"));
            copy.put("externalIdA", row.get("external_id_a"));
            copy.put("externalIdB", row.get("external_id_b"));
            Object signals = row.get("signals_json");
            if (signals != null) {
                String s = signals.toString();
                copy.put("signalsJson", s.length() > 800 ? s.substring(0, 800) + "..." : s);
            }
            out.add(copy);
        }
        return out;
    }

    private void shrinkGeoJson(Path source, Path target, int keep) throws Exception {
        var root = objectMapper.readTree(Files.readString(source));
        ArrayNode features = (ArrayNode) root.get("features");
        ArrayNode reduced = objectMapper.createArrayNode();
        for (int i = 0; i < Math.min(keep, features.size()); i++) {
            reduced.add(features.get(i));
        }
        ((ObjectNode) root).set("features", reduced);
        Files.writeString(target, objectMapper.writeValueAsString(root));
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required when validation is enabled");
        }
        return value;
    }

    private static HttpServer startServer() {
        try {
            try (var in = OsmRealIzmirImportValidationIT.class.getResourceAsStream(
                    "/fixtures/municipal/izum/otoparklar-sample.json")) {
                IZUM_BODY.set(in.readAllBytes());
            }
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/ibb/izum/otoparklar", exchange -> {
                byte[] body = IZUM_BODY.get();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();
            return server;
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }
}
