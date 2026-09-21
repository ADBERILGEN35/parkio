package com.parkio.parking.infrastructure.persistence.municipal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.parkio.parking.application.IzelmanImportApplicationService;
import com.parkio.parking.application.IzelmanImportResult;
import com.parkio.parking.application.MunicipalFacilityQueryService;
import com.parkio.parking.application.PublicExploreQueryService;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import com.parkio.parking.externalsource.izelman.IzelmanExternalId;
import com.parkio.parking.externalsource.izelman.IzelmanSourceKeys;
import com.parkio.parking.externalsource.izelman.SourceAgeClassification;
import com.parkio.parking.testsupport.PostgisTestImages;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/**
 * Isolated import of all four official İZELMAN inventory CSVs + Explore/map coverage probes.
 * Publication flags for Explore are enabled only inside this disposable PostGIS (not production).
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class OfficialIzelmanCsvIsolatedDryRunIT {
    private static final DockerImageName POSTGIS = PostgisTestImages.dockerImageName();
    private static final Path OFFICIAL_DIR = resolveOfficialDir();
    private static final String[] FACILITY_KEYS = {
        IzelmanSourceKeys.OPEN, IzelmanSourceKeys.CLOSED, IzelmanSourceKeys.BARRIER
    };
    private static final Map<String, Integer> EXPECTED_RAW = Map.of(
            IzelmanSourceKeys.OPEN, 11,
            IzelmanSourceKeys.CLOSED, 23,
            IzelmanSourceKeys.BARRIER, 17,
            IzelmanSourceKeys.ROADSIDE, 48);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS)
            .withDatabaseName("parkio_izelman_official_dry")
            .withUsername("parkio")
            .withPassword("parkio");

    @Autowired IzelmanImportApplicationService importService;
    @Autowired PublicExploreQueryService explore;
    @Autowired MunicipalFacilityQueryService municipal;
    @Autowired JdbcTemplate jdbc;

    /** Representative İzmir centers for Explore + authenticated /map coverage probes. */
    private static final List<double[]> CENTERS = List.of(
            new double[] {38.4036, 27.1100}, // Hatay / Karantina / Göztepe
            new double[] {38.4192, 27.1285}, // Konak
            new double[] {38.4380, 27.1420}, // Alsancak
            new double[] {38.4554, 27.1202}, // Karşıyaka
            new double[] {38.4622, 27.2200}, // Bornova
            new double[] {38.3860, 27.1740}  // Buca
    );

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
        registry.add("parkio.municipal.izelman.tariff-import-enabled", () -> "false");
        // Candidate-stack publication ON for disposable DB only.
        registry.add("parkio.municipal.izelman.facility-publication-enabled", () -> "true");
        registry.add("parkio.municipal.izelman.roadside-publication-enabled", () -> "true");
        registry.add("parkio.municipal.izelman.tariff-publication-enabled", () -> "false");
        registry.add("parkio.municipal.izelman.allowed-input-dir", () -> OFFICIAL_DIR.toString());
        registry.add("parkio.public-explore.enabled", () -> "true");
        registry.add("parkio.public-explore.allowed-source-families", () -> "IZUM,IZELMAN,OSM");
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
    void dryRunOfficialFacilityAndRoadsideSourcesWithoutDbWrites() {
        assumeOfficialPresent();
        Map<String, IzelmanImportResult> results = new LinkedHashMap<>();
        for (String key : List.of(
                IzelmanSourceKeys.OPEN,
                IzelmanSourceKeys.CLOSED,
                IzelmanSourceKeys.BARRIER,
                IzelmanSourceKeys.ROADSIDE)) {
            IzelmanImportResult dry = importService.importConfigured(key, true);
            results.put(key, dry);
            assertThat(dry.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
            assertThat(dry.dryRun()).isTrue();
            assertThat(dry.accepted()).isEqualTo(EXPECTED_RAW.get(key));
            assertThat(dry.ageClassification()).isEqualTo(SourceAgeClassification.HISTORICAL);
            assertThat(dry.inserted() + dry.updated()).isZero();
        }
        System.out.println("official_izelman_dry_run_counts");
        results.forEach((key, r) -> System.out.printf(
                "source=%s raw=%d accepted=%d rejected=%d duplicates=%d%n",
                key, r.recordsRead(), r.accepted(), r.rejected(), r.trueDuplicates()));
    }

    @Test
    void isolatedImportAllFourDatasetsIsIdempotentAndSeparatesUniqueFacilities() {
        assumeOfficialPresent();
        Map<String, IzelmanImportResult> firstPass = new LinkedHashMap<>();
        for (String key : List.of(
                IzelmanSourceKeys.OPEN,
                IzelmanSourceKeys.CLOSED,
                IzelmanSourceKeys.BARRIER,
                IzelmanSourceKeys.ROADSIDE)) {
            IzelmanImportResult first = importService.importConfigured(key, false);
            firstPass.put(key, first);
            assertThat(first.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
            assertThat(first.accepted()).isEqualTo(EXPECTED_RAW.get(key));
            assertThat(first.inserted()).isEqualTo(EXPECTED_RAW.get(key));
        }

        Map<String, IzelmanImportResult> secondPass = new LinkedHashMap<>();
        for (String key : firstPass.keySet()) {
            IzelmanImportResult second = importService.importConfigured(key, false);
            secondPass.put(key, second);
            assertThat(second.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
            assertThat(second.accepted()).isEqualTo(EXPECTED_RAW.get(key));
            assertThat(second.inserted()).isZero();
            assertThat(second.unchanged() + second.updated()).isEqualTo(EXPECTED_RAW.get(key));
        }

        long uniqueFacilities = jdbc.queryForObject(
                """
                SELECT count(DISTINCT f.id)
                FROM municipal_parking_facilities f
                JOIN municipal_facility_source_links l ON l.facility_id=f.id AND l.active=true
                JOIN municipal_data_sources d ON d.id=l.source_id
                WHERE d.source_key IN (?,?,?)
                """,
                Long.class,
                IzelmanSourceKeys.OPEN,
                IzelmanSourceKeys.CLOSED,
                IzelmanSourceKeys.BARRIER);
        long roadsideSegments = jdbc.queryForObject(
                "SELECT count(*) FROM municipal_roadside_segments", Long.class);
        long occupancy = jdbc.queryForObject("SELECT count(*) FROM municipal_occupancy_snapshots", Long.class);
        assertThat(uniqueFacilities).isEqualTo(11 + 23 + 17);
        assertThat(roadsideSegments).isEqualTo(48);
        assertThat(occupancy).isZero();

        // Barrier → RESTRICTED; open/closed default PUBLIC from mapper.
        Long restricted = jdbc.queryForObject(
                """
                SELECT count(*) FROM municipal_parking_facilities f
                JOIN municipal_facility_source_links l ON l.facility_id=f.id AND l.active=true
                JOIN municipal_data_sources d ON d.id=l.source_id
                WHERE d.source_key=? AND f.access_classification=?
                """,
                Long.class,
                IzelmanSourceKeys.BARRIER,
                MunicipalAccessClassification.RESTRICTED.name());
        assertThat(restricted).isEqualTo(17);

        // Hatay / Konak recovery identities from closed CSV (stable external ids).
        assertClosedFacilityPresent("KONAK KATLI", 38.4156298305857, 27.1293973233848, "KONAK");
        assertClosedFacilityPresent("HATAY PAZAR YERİ KATLI", 38.4035824266069, 27.1100478160301, "KONAK");

        System.out.println("official_izelman_isolated_all_four");
        firstPass.forEach((key, r) -> System.out.printf(
                "source=%s first_inserted=%d second_unchanged_or_updated=%d%n",
                key,
                r.inserted(),
                secondPass.get(key).unchanged() + secondPass.get(key).updated()));
        System.out.printf(
                "unique_facility_ids=%d roadside_segments=%d occupancy_snapshots=%d%n",
                uniqueFacilities,
                roadsideSegments,
                occupancy);
    }

    @Test
    void candidateExploreSeesIzelmanFacilitiesWithoutOccupancyInvention() {
        assumeOfficialPresent();
        for (String key : FACILITY_KEYS) {
            assertThat(importService.importConfigured(key, false).status())
                    .isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        }

        // Konak center — preview limit 6 must not shrink source family to IZUM.
        var result = explore.discover(new PublicExploreQueryService.DiscoveryQuery(38.4192, 27.1285, 5_000, 6));
        assertThat(result.municipalTotalInScope()).isGreaterThan(6);
        assertThat(result.facilities()).hasSize(6);
        assertThat(result.municipalHiddenCount()).isEqualTo(result.municipalTotalInScope() - 6);
        result.facilities().forEach(view -> {
            assertThat(view.availableSpaces()).isNull();
            assertThat(view.availabilityFreshness().name()).isEqualTo("UNAVAILABLE");
            assertThat(view.attribution()).isNotBlank();
            assertThat(view.sourceLabel()).containsIgnoringCase("izelman");
            assertThat(view.accessClassification()).isNotNull();
        });
        System.out.printf(
                "explore_konak total=%d visible=%d hidden=%d%n",
                result.municipalTotalInScope(),
                result.facilities().size(),
                result.municipalHiddenCount());
    }

    @Test
    void candidateCentersCoverExploreAndAuthenticatedMapWithoutFakeOccupancy() {
        assumeOfficialPresent();
        for (String key : FACILITY_KEYS) {
            assertThat(importService.importConfigured(key, false).status())
                    .isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        }

        System.out.println("candidate_center_coverage");
        for (double[] center : CENTERS) {
            double lat = center[0];
            double lng = center[1];
            var exploreHit = explore.discover(
                    new PublicExploreQueryService.DiscoveryQuery(lat, lng, 5_000, 6));
            var mapHit = municipal.nearby(lat, lng, 5_000, 100);
            assertThat(exploreHit.municipalTotalInScope())
                    .as("explore inventory at %.4f,%.4f", lat, lng)
                    .isGreaterThan(0);
            assertThat(exploreHit.facilities().size()).isLessThanOrEqualTo(6);
            assertThat(mapHit.size()).isGreaterThan(0);
            assertThat(mapHit.size()).isLessThanOrEqualTo(100);
            mapHit.forEach(view -> {
                assertThat(view.availableSpaces()).isNull();
                assertThat(view.accessClassification()).isNotNull();
            });
            boolean mapCapped = mapHit.size() == 100;
            System.out.printf(
                    "center=%.4f,%.4f explore_total=%d explore_visible=%d map_count=%d map_capped=%s%n",
                    lat,
                    lng,
                    exploreHit.municipalTotalInScope(),
                    exploreHit.facilities().size(),
                    mapHit.size(),
                    mapCapped);
        }
    }

    private void assertClosedFacilityPresent(String name, double lat, double lng, String district) {
        String externalId = IzelmanExternalId.of(IzelmanSourceKeys.CLOSED, name, lat, lng, district);
        // Prefer exact external_id match when coordinates from CSV; fallback name+source.
        Integer byId = jdbc.queryForObject(
                """
                SELECT count(*) FROM municipal_facility_source_links l
                JOIN municipal_data_sources d ON d.id=l.source_id
                WHERE d.source_key=? AND l.external_id=? AND l.active=true
                """,
                Integer.class,
                IzelmanSourceKeys.CLOSED,
                externalId);
        if (byId != null && byId > 0) {
            assertThat(byId).isEqualTo(1);
            Integer occ = jdbc.queryForObject(
                    """
                    SELECT count(*) FROM municipal_occupancy_snapshots o
                    JOIN municipal_facility_source_links l ON l.facility_id=o.facility_id
                    WHERE l.external_id=?
                    """,
                    Integer.class,
                    externalId);
            assertThat(occ).isZero();
            return;
        }
        Integer byName = jdbc.queryForObject(
                """
                SELECT count(*) FROM municipal_parking_facilities f
                JOIN municipal_facility_source_links l ON l.facility_id=f.id AND l.active=true
                JOIN municipal_data_sources d ON d.id=l.source_id
                WHERE d.source_key=? AND upper(f.display_name) LIKE upper(?)
                """,
                Integer.class,
                IzelmanSourceKeys.CLOSED,
                "%" + name.split(" ")[0] + "%");
        assertThat(byName).isGreaterThanOrEqualTo(1);
    }

    private void assumeOfficialPresent() {
        assumeTrue(OFFICIAL_DIR != null && Files.isDirectory(OFFICIAL_DIR), "official IZELMAN dir missing");
        for (String key : EXPECTED_RAW.keySet()) {
            assumeTrue(Files.isRegularFile(OFFICIAL_DIR.resolve(key + ".csv")), "missing " + key);
        }
    }

    private static Path resolveOfficialDir() {
        String env = System.getenv("PARKIO_IZELMAN_OFFICIAL_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env).toAbsolutePath().normalize();
        }
        Path[] candidates = {
            Path.of("agent-tools/parkio-izmir-coverage-expansion-01/sources/izelman/parkio-named")
                    .toAbsolutePath()
                    .normalize(),
            Path.of("../agent-tools/parkio-izmir-coverage-expansion-01/sources/izelman/parkio-named")
                    .toAbsolutePath()
                    .normalize(),
            Path.of("../../agent-tools/parkio-izmir-coverage-expansion-01/sources/izelman/parkio-named")
                    .toAbsolutePath()
                    .normalize()
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return candidates[0];
    }
}
