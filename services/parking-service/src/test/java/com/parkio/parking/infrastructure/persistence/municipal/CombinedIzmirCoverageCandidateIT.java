package com.parkio.parking.infrastructure.persistence.municipal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.IzelmanImportApplicationService;
import com.parkio.parking.application.MunicipalFacilityQueryService;
import com.parkio.parking.application.MunicipalFacilitySyncService;
import com.parkio.parking.application.OsmImportApplicationService;
import com.parkio.parking.application.OsmImportResult;
import com.parkio.parking.application.PublicExploreQueryService;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import com.parkio.parking.externalsource.izelman.IzelmanSourceKeys;
import com.parkio.parking.infrastructure.izum.IzumMunicipalParkingAdapter;
import com.parkio.parking.testsupport.PostgisTestImages;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
 * Combined candidate inventory: IZUM + all four İZELMAN datasets + İzmir OSM.
 * Opt-in only — does not re-run production imports.
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "parkio.combined.izmir.candidate", matches = "true")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class CombinedIzmirCoverageCandidateIT {
    private static final DockerImageName POSTGIS = PostgisTestImages.dockerImageName();
    private static final Path OFFICIAL_DIR = resolveOfficialDir();
    private static final Path GEOJSON = Path.of(requirePropOrEnv(
                    "parkio.osm.real.izmir.geojson", "PARKIO_OSM_REAL_IZMIR_GEOJSON"))
            .toAbsolutePath().normalize();
    private static final Path REPORT_DIR = Path.of(firstNonBlank(
                    System.getProperty("parkio.combined.report.dir"),
                    System.getenv("PARKIO_COMBINED_REPORT_DIR"),
                    "agent-tools/parkio-izmir-coverage-expansion-01"))
            .toAbsolutePath().normalize();

    private static final List<Map.Entry<String, double[]>> CENTERS = List.of(
            Map.entry("Hatay", new double[] {38.4036, 27.1100}),
            Map.entry("Konak", new double[] {38.4192, 27.1285}),
            Map.entry("Alsancak", new double[] {38.4380, 27.1420}),
            Map.entry("Karsiyaka", new double[] {38.4554, 27.1202}),
            Map.entry("Bornova", new double[] {38.4622, 27.2200}),
            Map.entry("Buca", new double[] {38.3860, 27.1740}));

    private static final AtomicReference<byte[]> IZUM_BODY = new AtomicReference<>();
    private static final HttpServer IZUM_SERVER = startServer();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS)
            .withDatabaseName("parkio_combined_izmir")
            .withUsername("parkio")
            .withPassword("parkio");

    @Autowired IzelmanImportApplicationService izelman;
    @Autowired OsmImportApplicationService osm;
    @Autowired MunicipalFacilitySyncService municipalSync;
    @Autowired MunicipalFacilityQueryService municipal;
    @Autowired PublicExploreQueryService explore;
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
        registry.add("parkio.municipal.izelman.enabled", () -> "true");
        registry.add("parkio.municipal.izelman.facility-import-enabled", () -> "true");
        registry.add("parkio.municipal.izelman.roadside-import-enabled", () -> "true");
        registry.add("parkio.municipal.izelman.tariff-import-enabled", () -> "false");
        registry.add("parkio.municipal.izelman.facility-publication-enabled", () -> "true");
        registry.add("parkio.municipal.izelman.roadside-publication-enabled", () -> "true");
        registry.add("parkio.municipal.izelman.allowed-input-dir", () -> OFFICIAL_DIR.toString());
        registry.add("parkio.municipal.osm.import-enabled", () -> "true");
        registry.add("parkio.municipal.osm.conflation-enabled", () -> "true");
        // Conservative: record proposals only — do not auto-reassign links.
        registry.add("parkio.municipal.osm.auto-match-enabled", () -> "false");
        registry.add("parkio.municipal.osm.publication-enabled", () -> "true");
        registry.add("parkio.municipal.osm.local-input-path", () -> GEOJSON.toString());
        registry.add("parkio.municipal.osm.allowed-input-dir", () -> GEOJSON.getParent().toString());
        registry.add("parkio.municipal.osm.max-input-bytes", () -> "52428800");
        registry.add("parkio.public-explore.enabled", () -> "true");
        registry.add("parkio.public-explore.allowed-source-families", () -> "IZUM,IZELMAN,OSM");
    }

    @Test
    void combinedInventoryCentersExploreAndMap() throws Exception {
        assumeTrue(Files.isDirectory(OFFICIAL_DIR), "IZELMAN dir missing");
        assumeTrue(Files.isRegularFile(GEOJSON), "OSM geojson missing");
        Files.createDirectories(REPORT_DIR);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("startedAt", Instant.now().toString());
        report.put("autoMatchEnabled", false);

        var izum = municipalSync.sync(IzumMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(izum.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        long izumActive = countActive("izmir-izum-otoparklar");
        report.put("izumActive", izumActive);
        assertThat(izumActive).isPositive();

        for (String key : List.of(
                IzelmanSourceKeys.OPEN, IzelmanSourceKeys.CLOSED,
                IzelmanSourceKeys.BARRIER, IzelmanSourceKeys.ROADSIDE)) {
            assertThat(izelman.importConfigured(key, false).status())
                    .isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        }

        OsmImportResult osmFirst = osm.importFromConfiguredPath(false);
        assertThat(osmFirst.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        report.put("osmExtracted", osmFirst.extracted());
        report.put("osmRejected", osmFirst.rejected());
        report.put("osmAutoMatchedProposals", osmFirst.autoMatched());
        report.put("osmReviewRequiredProposals", osmFirst.reviewRequired());

        long izelmanFacilities = countActiveFamily("izelman");
        long osmFacilities = countActive("osm-geofabrik-turkey");
        long uniqueFacilities = jdbc.queryForObject(
                "SELECT count(*) FROM municipal_parking_facilities WHERE active=true", Long.class);
        long roadsideTotal = jdbc.queryForObject(
                "SELECT count(*) FROM municipal_roadside_segments WHERE active=true", Long.class);
        long roadsideWithGeom = jdbc.queryForObject(
                """
                SELECT count(*) FROM municipal_roadside_segments
                WHERE active=true AND location IS NOT NULL
                """,
                Long.class);
        long roadsidePublished = jdbc.queryForObject(
                """
                SELECT count(*) FROM municipal_roadside_segments
                WHERE active=true AND publication_status='PUBLISHED'
                """,
                Long.class);
        long roadsideDiscoverable = jdbc.queryForObject(
                """
                SELECT count(*) FROM municipal_roadside_segments
                WHERE active=true AND location IS NOT NULL AND publication_status='PUBLISHED'
                """,
                Long.class);
        long roadsideNoGeom = roadsideTotal - roadsideWithGeom;
        long mergedLinks = jdbc.queryForObject(
                """
                SELECT count(*) FROM municipal_facility_source_links l
                JOIN municipal_data_sources d ON d.id=l.source_id
                WHERE l.active=true AND d.source_key='osm-geofabrik-turkey'
                  AND l.facility_id IN (
                    SELECT l2.facility_id FROM municipal_facility_source_links l2
                    JOIN municipal_data_sources d2 ON d2.id=l2.source_id
                    WHERE l2.active=true AND d2.source_key='izmir-izum-otoparklar'
                  )
                """,
                Long.class);

        List<Map<String, Object>> decisions = jdbc.queryForList("""
                SELECT decision, total_score AS score, external_id_a, external_id_b, decision_reason AS reason
                FROM municipal_facility_conflation_decisions
                WHERE superseded=false
                ORDER BY decision, total_score DESC NULLS LAST
                """);
        report.put("izelmanFacilityActive", izelmanFacilities);
        report.put("osmFacilityActive", osmFacilities);
        report.put("uniqueActiveFacilities", uniqueFacilities);
        report.put("roadsideActiveTotal", roadsideTotal);
        report.put("roadsideWithGeometry", roadsideWithGeom);
        report.put("roadsidePublished", roadsidePublished);
        report.put("roadsideDiscoverableNearbyApi", roadsideDiscoverable);
        report.put("roadsideWithoutGeometry", roadsideNoGeom);
        report.put("roadsideSurface",
                "Published roadside segments surface via /api/v1/parking/roadside/nearby and are "
                        + "merged into Public Explore + authenticated /map when IZELMAN is allowlisted "
                        + "(ON_STREET, UNKNOWN access, UNAVAILABLE occupancy). Not municipal_facility rows.");
        report.put("uniqueFacilityRecordsNote",
                "uniqueActiveFacilities counts distinct facility table rows across sources "
                        + "(IZUM+İZELMAN facilities+OSM). It is not a count of unique physical parking "
                        + "locations; unresolved cross-source duplicates may remain when "
                        + "auto-match-enabled=false. Roadside (48) is counted separately.");
        assertThat(roadsideWithGeom).isEqualTo(48);
        assertThat(roadsidePublished).isEqualTo(48);
        assertThat(roadsideDiscoverable).isEqualTo(48);
        report.put("osmLinksSharingFacilityWithIzum", mergedLinks);
        report.put("conflationDecisions", decisions);
        report.put("note",
                "auto-match-enabled=false → AUTO_MATCHED rows are proposals only; "
                        + "OSM links are not reassigned onto IZUM facilities.");

        // Unique publishable facility inventory = active facilities (IZUM+IZELMAN+OSM, unreassigned).
        // Roadside is a separate surface (not facility rows).
        assertThat(izelmanFacilities).isEqualTo(51);
        assertThat(roadsideTotal).isEqualTo(48);
        assertThat(osmFacilities).isEqualTo(osmFirst.extracted());
        // With auto-match off, OSM and IZUM stay separate facility rows even if a proposal exists.
        assertThat(mergedLinks).isZero();
        assertThat(uniqueFacilities).isEqualTo(izumActive + 51 + osmFacilities);

        List<Map<String, Object>> centers = new java.util.ArrayList<>();
        for (var center : CENTERS) {
            String name = center.getKey();
            double lat = center.getValue()[0];
            double lng = center.getValue()[1];
            var exploreHit = explore.discover(
                    new PublicExploreQueryService.DiscoveryQuery(lat, lng, 5_000, 6));
            var mapHit = municipal.nearby(lat, lng, 5_000, 100);
            long roadsideNear = jdbc.queryForObject(
                    """
                    SELECT count(*) FROM municipal_roadside_segments s
                    WHERE s.active=true AND s.publication_status='PUBLISHED' AND s.location IS NOT NULL
                      AND ST_DWithin(s.location, ST_SetSRID(ST_MakePoint(?,?),4326)::geography, 5000)
                    """,
                    Long.class, lng, lat);

            Set<String> exploreFamilies = exploreHit.facilities().stream()
                    .map(f -> familyOf(f.sourceLabel()))
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            Set<String> mapFamilies = mapHit.stream()
                    .map(f -> familyOf(f.sourceLabel()))
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            Set<String> accessSeen = mapHit.stream()
                    .map(f -> f.accessClassification() == null
                            ? "NULL" : f.accessClassification().name())
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            long osmOccLeak = mapHit.stream()
                    .filter(f -> f.sourceLabel() != null && f.sourceLabel().toLowerCase().contains("openstreetmap"))
                    .filter(f -> f.availableSpaces() != null
                            || f.freshness() == MunicipalOccupancyFreshness.LIVE
                            || f.freshness() == MunicipalOccupancyFreshness.AGING)
                    .count();
            long izelmanOccLeak = mapHit.stream()
                    .filter(f -> f.sourceLabel() != null && f.sourceLabel().toUpperCase().contains("IZELMAN"))
                    .filter(f -> f.availableSpaces() != null)
                    .count();

            assertThat(exploreHit.municipalTotalInScope()).isGreaterThan(0);
            if (roadsideNear > 0) {
                assertThat(exploreHit.municipalTotalInScope()).isGreaterThanOrEqualTo(roadsideNear);
            }
            assertThat(exploreHit.facilities().size()).isLessThanOrEqualTo(6);
            assertThat(mapHit.size()).isGreaterThan(0);
            assertThat(mapHit.size()).isLessThanOrEqualTo(100);
            assertThat(osmOccLeak).isZero();
            assertThat(izelmanOccLeak).isZero();
            mapHit.forEach(f -> {
                assertThat(f.accessClassification()).isNotNull();
                assertThat(f.attribution()).isNotBlank();
            });
            List<String> exploreRoadsideIds = exploreHit.facilities().stream()
                    .filter(f -> PublicExploreQueryService.IZELMAN_ROADSIDE_SOURCE_LABEL.equals(f.sourceLabel()))
                    .map(f -> f.id().toString())
                    .toList();
            exploreHit.facilities().stream()
                    .filter(f -> PublicExploreQueryService.IZELMAN_ROADSIDE_SOURCE_LABEL.equals(f.sourceLabel()))
                    .forEach(f -> {
                        assertThat(f.facilityType()).isEqualTo(MunicipalFacilityType.ON_STREET);
                        assertThat(f.accessClassification()).isEqualTo(MunicipalAccessClassification.UNKNOWN);
                        assertThat(f.availabilityFreshness()).isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
                        assertThat(f.availableSpaces()).isNull();
                        assertThat(f.attribution())
                                .isEqualTo(PublicExploreQueryService.IZELMAN_ROADSIDE_ATTRIBUTION);
                    });
            List<String> representativeRoadsideIds = jdbc.query(
                    """
                    SELECT s.id::text FROM municipal_roadside_segments s
                    WHERE s.active=true AND s.publication_status='PUBLISHED' AND s.location IS NOT NULL
                      AND ST_DWithin(s.location, ST_SetSRID(ST_MakePoint(?,?),4326)::geography, 5000)
                    ORDER BY ST_Distance(s.location, ST_SetSRID(ST_MakePoint(?,?),4326)::geography)
                    LIMIT 3
                    """,
                    (rs, rowNum) -> rs.getString(1),
                    lng, lat, lng, lat);

            boolean capped = mapHit.size() == 100;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("center", name);
            row.put("lat", lat);
            row.put("lng", lng);
            row.put("exploreTotal", exploreHit.municipalTotalInScope());
            row.put("exploreVisible", exploreHit.facilities().size());
            row.put("exploreRoadsideVisibleIds", exploreRoadsideIds);
            row.put("representativeRoadsideIdsInRadius", representativeRoadsideIds);
            row.put("exploreFamiliesSample", exploreFamilies);
            row.put("mapCount", mapHit.size());
            row.put("mapCappedAt100", capped);
            row.put("mapFamiliesSample", mapFamilies);
            row.put("accessClassificationsSeen", accessSeen);
            row.put("roadsideWithGeometryInRadius", roadsideNear);
            row.put("continuation", capped
                    ? "reduce_radius_via_municipal_radius_control — first 100 nearest facilities; "
                            + "map zoom alone does not change the API search"
                    : "within_cap");
            centers.add(row);
            System.out.printf(
                    "combined_center=%s explore=%d/%d map=%d capped=%s roadside_geom=%d families=%s%n",
                    name,
                    exploreHit.facilities().size(),
                    exploreHit.municipalTotalInScope(),
                    mapHit.size(),
                    capped,
                    roadsideNear,
                    mapFamilies);
        }
        report.put("centers", centers);
        report.put("finishedAt", Instant.now().toString());

        Path out = REPORT_DIR.resolve("combined-candidate-coverage-report.json");
        Files.writeString(out, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("combined_report=" + out);
        System.out.printf(
                "unique_active_facilities=%d izum=%d izelman_facilities=%d osm=%d "
                        + "roadside=%d (geom=%d published=%d discoverable=%d)%n",
                uniqueFacilities, izumActive, izelmanFacilities, osmFacilities,
                roadsideTotal, roadsideWithGeom, roadsidePublished, roadsideDiscoverable);
    }

    private long countActive(String sourceKey) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM municipal_parking_facilities f
                JOIN municipal_facility_source_links l ON l.facility_id=f.id AND l.active=true
                JOIN municipal_data_sources d ON d.id=l.source_id
                WHERE d.source_key=? AND f.active=true
                """, Long.class, sourceKey);
        return n == null ? 0 : n;
    }

    private long countActiveFamily(String familyKey) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT f.id) FROM municipal_parking_facilities f
                JOIN municipal_facility_source_links l ON l.facility_id=f.id AND l.active=true
                JOIN municipal_data_sources d ON d.id=l.source_id
                WHERE d.family_key=? AND f.active=true AND d.source_key IN (?,?,?)
                """,
                Long.class,
                familyKey,
                IzelmanSourceKeys.OPEN,
                IzelmanSourceKeys.CLOSED,
                IzelmanSourceKeys.BARRIER);
        return n == null ? 0 : n;
    }

    private static String familyOf(String sourceLabel) {
        if (sourceLabel == null) {
            return "UNKNOWN";
        }
        String s = sourceLabel.toLowerCase();
        if (s.contains("izelman")) {
            return "IZELMAN";
        }
        if (s.contains("openstreetmap") || s.contains("geofabrik") || s.contains("osm")) {
            return "OSM";
        }
        if (s.contains("izum") || s.contains("ibb")) {
            return "IZUM";
        }
        return sourceLabel;
    }

    private static Path resolveOfficialDir() {
        String fromProp = System.getProperty("parkio.izelman.official.dir");
        if (fromProp != null && !fromProp.isBlank()) {
            return Path.of(fromProp).toAbsolutePath().normalize();
        }
        String env = System.getenv("PARKIO_IZELMAN_OFFICIAL_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env).toAbsolutePath().normalize();
        }
        return Path.of(
                        "agent-tools/parkio-izmir-coverage-expansion-01/sources/izelman/parkio-named")
                .toAbsolutePath().normalize();
    }

    private static String requirePropOrEnv(String prop, String envName) {
        String fromProp = System.getProperty(prop);
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp;
        }
        String value = System.getenv(envName);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(prop + " or " + envName + " required for combined candidate IT");
        }
        return value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static HttpServer startServer() {
        try {
            try (var in = CombinedIzmirCoverageCandidateIT.class.getResourceAsStream(
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
