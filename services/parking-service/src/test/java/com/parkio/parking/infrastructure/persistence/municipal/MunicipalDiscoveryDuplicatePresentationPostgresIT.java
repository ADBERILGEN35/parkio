package com.parkio.parking.infrastructure.persistence.municipal;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.MunicipalFacilityQueryService;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.infrastructure.config.RegistryProperties;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.parkio.parking.testsupport.PostgisTestImages;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class MunicipalDiscoveryDuplicatePresentationPostgresIT {
    private static final DockerImageName POSTGIS =
            PostgisTestImages.dockerImageName();

    private static final UUID IZUM = UUID.fromString("00000000-0000-0000-0000-000000007001");
    private static final UUID OSM_DUP = UUID.fromString("00000000-0000-0000-0000-000000007002");
    private static final UUID OSM_DISTINCT = UUID.fromString("00000000-0000-0000-0000-000000007003");
    private static final UUID OSM_FAR = UUID.fromString("00000000-0000-0000-0000-000000007004");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS)
            .withDatabaseName("parkio_discovery_dup_it")
            .withUsername("parkio")
            .withPassword("parkio");

    @Autowired MunicipalFacilityQueryService query;
    @Autowired JdbcClient jdbc;

        @Autowired RegistryProperties registryProperties;

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
        registry.add("parkio.municipal.discovery.duplicate-presentation-enabled", () -> "true");
        registry.add("parkio.municipal.discovery.duplicate-radius-meters", () -> "100");
        registry.add("parkio.municipal.discovery.overfetch-factor", () -> "2");
        registry.add("parkio.municipal.discovery.overfetch-absolute-max", () -> "200");
        registry.add("parkio.municipal.registry.automatic-linking-enabled", () -> "false");
    }

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM municipal_occupancy_snapshots").update();
        jdbc.sql("DELETE FROM municipal_source_sync_runs WHERE correlation_id = 'discovery-dup-it'").update();
        jdbc.sql("DELETE FROM municipal_facility_source_links").update();
        jdbc.sql("DELETE FROM municipal_parking_facilities").update();

        insertFacility(IZUM, "Konak Otoparki", "OFF_STREET", "Konak", 38.42000, 27.14000, 120);
        insertFacility(OSM_DUP, "Konak Otoparki", "OFF_STREET", "Konak", 38.42005, 27.14005, 120);
        insertFacility(OSM_DISTINCT, "Alsancak Garage", "OFF_STREET", "Konak", 38.42150, 27.14150, 60);
        insertFacility(OSM_FAR, "Konak Otoparki", "OFF_STREET", "Bornova", 38.46000, 27.22000, 120);

        insertLink(IZUM, MunicipalSourceIdentity.IZUM, "izum-dup-1");
        insertLink(OSM_DUP, MunicipalSourceIdentity.OSM, "osm-dup-1");
        insertLink(OSM_DISTINCT, MunicipalSourceIdentity.OSM, "osm-distinct-1");
        insertLink(OSM_FAR, MunicipalSourceIdentity.OSM, "osm-far-1");

        jdbc.sql("""
                UPDATE municipal_parking_facilities
                SET primary_source_key = :key WHERE id = :id
                """).param("key", MunicipalSourceIdentity.IZUM).param("id", IZUM).update();
        jdbc.sql("""
                UPDATE municipal_parking_facilities
                SET primary_source_key = :key WHERE id = :id
                """).param("key", MunicipalSourceIdentity.OSM).param("id", OSM_DUP).update();
        jdbc.sql("""
                UPDATE municipal_parking_facilities
                SET primary_source_key = :key WHERE id = :id
                """).param("key", MunicipalSourceIdentity.OSM).param("id", OSM_DISTINCT).update();
        jdbc.sql("""
                UPDATE municipal_parking_facilities
                SET primary_source_key = :key WHERE id = :id
                """).param("key", MunicipalSourceIdentity.OSM).param("id", OSM_FAR).update();

        UUID syncRunId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO municipal_source_sync_runs(
                  id, source_id, correlation_id, started_at, completed_at, status,
                  records_received, records_accepted, records_rejected, records_inserted,
                  records_updated, records_unchanged, occupancy_inserted)
                SELECT :id, id, 'discovery-dup-it', now(), now(), 'SUCCESS',
                       1, 1, 0, 0, 0, 0, 1
                FROM municipal_data_sources WHERE source_key = :sourceKey
                """)
                .param("id", syncRunId)
                .param("sourceKey", MunicipalSourceIdentity.IZUM)
                .update();
        jdbc.sql("""
                INSERT INTO municipal_occupancy_snapshots(
                  id, facility_id, source_id, source_link_id, sync_run_id, source_observed_at, fetched_at,
                  timestamp_provenance, capacity_total, occupied_spaces, available_spaces,
                  occupancy_status, raw_record_hash, created_at)
                SELECT :id, :facility, ds.id, lx.id, :runId, now(), now(),
                       'SOURCE', 120, 20, 100, 'LIVE', 'occ-hash-1', now()
                FROM municipal_data_sources ds
                JOIN municipal_facility_source_links lx ON lx.source_id = ds.id AND lx.facility_id = :facility
                WHERE ds.source_key = :sourceKey
                LIMIT 1
                """)
                .param("id", UUID.randomUUID())
                .param("facility", IZUM)
                .param("runId", syncRunId)
                .param("sourceKey", MunicipalSourceIdentity.IZUM)
                .update();
    }

    @Test
    void nearbySuppressesStrongDuplicateKeepsDistinctAndDetailResolves() {
        Map<String, Long> before = mutationCounts();

        List<MunicipalFacilityQueryService.FacilityView> nearby =
                query.nearby(38.42000, 27.14000, 5000, 10);

        assertThat(nearby).extracting(MunicipalFacilityQueryService.FacilityView::id)
                .contains(IZUM, OSM_DISTINCT)
                .doesNotContain(OSM_DUP);
        assertThat(nearby.stream().filter(v -> v.id().equals(IZUM)).findFirst().orElseThrow()
                .availableSpaces()).isEqualTo(100);
        assertThat(nearby.stream().filter(v -> v.id().equals(IZUM)).findFirst().orElseThrow()
                .freshness()).isEqualTo(MunicipalOccupancyFreshness.LIVE);

        assertThat(query.findById(OSM_DUP)).isPresent();
        assertThat(query.findById(OSM_DUP).orElseThrow().availableSpaces()).isNull();
        assertThat(query.findById(OSM_DUP).orElseThrow().attribution())
                .isEqualTo("OpenStreetMap contributors");

        List<MunicipalFacilityQueryService.FacilityView> again =
                query.nearby(38.42000, 27.14000, 5000, 10);
        assertThat(again).extracting(MunicipalFacilityQueryService.FacilityView::id)
                .containsExactlyElementsOf(
                        nearby.stream().map(MunicipalFacilityQueryService.FacilityView::id).toList());

        assertThat(mutationCounts()).isEqualTo(before);
        assertThat(registryProperties.isAutomaticLinkingEnabled()).isFalse();
        assertThat(registryProperties.isReviewedLinkingEnabled()).isFalse();
    }

    @Test
    void hardConflictAndDistanceOnlyPeersRemainVisible() {
        // Replace OSM_DUP with an ON_STREET hard-conflict peer at the same coords.
        jdbc.sql("DELETE FROM municipal_facility_source_links WHERE facility_id = :id")
                .param("id", OSM_DUP).update();
        jdbc.sql("DELETE FROM municipal_parking_facilities WHERE id = :id")
                .param("id", OSM_DUP).update();
        insertFacility(OSM_DUP, "Konak Otoparki", "ON_STREET", "Konak", 38.42005, 27.14005, 120);
        insertLink(OSM_DUP, MunicipalSourceIdentity.OSM, "osm-type-conflict");
        jdbc.sql("""
                UPDATE municipal_parking_facilities SET primary_source_key = :key WHERE id = :id
                """).param("key", MunicipalSourceIdentity.OSM).param("id", OSM_DUP).update();

        List<MunicipalFacilityQueryService.FacilityView> nearby =
                query.nearby(38.42000, 27.14000, 5000, 10);

        assertThat(nearby).extracting(MunicipalFacilityQueryService.FacilityView::id)
                .contains(IZUM, OSM_DUP, OSM_DISTINCT);
    }

    private Map<String, Long> mutationCounts() {
        return Map.of(
                "candidates", count("SELECT count(*) FROM municipal_link_candidates"),
                "links", count("SELECT count(*) FROM municipal_facility_source_links"),
                "aliases", count("SELECT count(*) FROM municipal_facility_aliases"),
                "occupancy", count("SELECT count(*) FROM municipal_occupancy_snapshots"),
                "tariffs", count("SELECT count(*) FROM municipal_tariff_assignments"),
                "review", count("SELECT count(*) FROM municipal_link_review_audit"));
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private void insertFacility(
            UUID id, String name, String type, String address, double lat, double lng, int capacity) {
        jdbc.sql("""
                INSERT INTO municipal_parking_facilities(
                  id,operator_name,facility_type,access_classification,display_name,address_text,
                  latitude,longitude,location,capacity_total,active,lifecycle_state,created_at,updated_at)
                VALUES (:id,'IZELMAN',:type,'PUBLIC',:name,:address,
                  :lat,:lng,ST_SetSRID(ST_MakePoint(:lng,:lat),4326)::geography,:capacity,true,'ACTIVE',now(),now())
                """)
                .param("id", id)
                .param("type", type)
                .param("name", name)
                .param("address", address)
                .param("lat", lat)
                .param("lng", lng)
                .param("capacity", capacity)
                .update();
    }

    private void insertLink(UUID facilityId, String sourceKey, String externalId) {
        jdbc.sql("""
                INSERT INTO municipal_facility_source_links(
                  id,facility_id,source_id,external_id,source_name,source_metadata_json,raw_record_hash,
                  first_seen_at,last_seen_at,last_successful_sync_at,active,created_at,updated_at)
                SELECT :id,:facility,id,:external,:name,'{"district":"Konak"}',:hash,
                  now(),now(),now(),true,now(),now()
                FROM municipal_data_sources WHERE source_key=:sourceKey
                """)
                .param("id", UUID.randomUUID())
                .param("facility", facilityId)
                .param("external", externalId)
                .param("name", "facility")
                .param("hash", externalId + "-v1")
                .param("sourceKey", sourceKey)
                .update();
    }
}
