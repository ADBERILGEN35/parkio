package com.parkio.parking.infrastructure.persistence.municipal;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.MunicipalFacilityQueryService;
import com.parkio.parking.application.RegistryPublicationService;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.infrastructure.config.RegistryProperties;
import com.parkio.parking.presentation.dto.MunicipalFacilityResponse;
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
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class MunicipalProvenancePublicationPostgresIT {
    private static final DockerImageName POSTGIS =
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres");

    private static final UUID IZUM = UUID.fromString("00000000-0000-0000-0000-000000009101");
    private static final UUID OSM = UUID.fromString("00000000-0000-0000-0000-000000009102");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS)
            .withDatabaseName("parkio_provenance_pub_it")
            .withUsername("parkio")
            .withPassword("parkio");

    @Autowired MunicipalFacilityQueryService query;
    @Autowired RegistryPublicationService publication;
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
        registry.add("parkio.kafka.really.enabled", () -> "false");
        registry.add("parkio.kafka.moderation-consumer.enabled", () -> "false");
        registry.add("parkio.kafka.ai-validation-consumer.enabled", () -> "false");
        registry.add("parkio.lifecycle.parking-expiry.enabled", () -> "false");
        registry.add("parkio.lifecycle.moderation-timeout.enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
        registry.add("parkio.municipal.enabled", () -> "true");
        registry.add("parkio.municipal.osm.publication-enabled", () -> "true");
        // DATA-WP-11: simulate canonical/hosted-beta default-on; tests toggle kill-switch.
        registry.add("parkio.municipal.registry.provenance-publication-enabled", () -> "true");
        registry.add("parkio.municipal.registry.automatic-linking-enabled", () -> "false");
        registry.add("parkio.municipal.registry.reviewed-linking-enabled", () -> "false");
        registry.add("parkio.municipal.registry.candidate-generation-enabled", () -> "false");
        registry.add("parkio.municipal.registry.provenance-ingest-write-enabled", () -> "true");
    }

    @BeforeEach
    void seed() {
        // Reset kill-switch mutations between methods (RegistryProperties is a mutable singleton).
        registryProperties.setProvenancePublicationEnabled(true);

        jdbc.sql("DELETE FROM municipal_facility_field_provenance").update();
        jdbc.sql("DELETE FROM municipal_occupancy_snapshots").update();
        jdbc.sql("DELETE FROM municipal_facility_source_links").update();
        jdbc.sql("DELETE FROM municipal_parking_facilities").update();

        insertFacility(IZUM, "Konak Otopark", "OFF_STREET", "Konak", 38.42000, 27.14000, 120);
        insertFacility(OSM, "Alsancak Garage", "OFF_STREET", "Konak", 38.42150, 27.14150, 60);
        insertLink(IZUM, MunicipalSourceIdentity.IZUM, "izum-pub-1");
        insertLink(OSM, MunicipalSourceIdentity.OSM, "osm-pub-1");
        jdbc.sql("""
                UPDATE municipal_parking_facilities
                SET primary_source_key = :key WHERE id = :id
                """).param("key", MunicipalSourceIdentity.IZUM).param("id", IZUM).update();
        jdbc.sql("""
                UPDATE municipal_parking_facilities
                SET primary_source_key = :key WHERE id = :id
                """).param("key", MunicipalSourceIdentity.OSM).param("id", OSM).update();

        insertProvenance(IZUM, "NAME", MunicipalSourceIdentity.IZUM, "CURRENT");
        insertProvenance(IZUM, "COORDINATES", MunicipalSourceIdentity.IZUM, "CURRENT");
        insertProvenance(IZUM, "ADDRESS", MunicipalSourceIdentity.IZUM, "CURRENT");
        insertProvenance(IZUM, "TARIFF_ASSIGNMENT", "izelman-open-parking-facilities", "REVIEW_REQUIRED");
        insertProvenance(IZUM, "ACCESS", MunicipalSourceIdentity.OSM, "CURRENT");
        insertProvenance(OSM, "NAME", MunicipalSourceIdentity.OSM, "CURRENT");
        insertProvenance(OSM, "ATTRIBUTION", MunicipalSourceIdentity.OSM, "CURRENT");
    }

    @Test
    void defaultOnEnrichesWithoutExplicitToggle() {
        assertThat(registryProperties.isProvenancePublicationEnabled()).isTrue();
        assertThat(registryProperties.isProvenanceIngestWriteEnabled()).isTrue();
        assertThat(registryProperties.isAutomaticLinkingEnabled()).isFalse();
        assertThat(registryProperties.isReviewedLinkingEnabled()).isFalse();

        Map<String, Long> before = mutationCounts();
        MunicipalFacilityResponse izum = MunicipalFacilityResponse.from(
                query.findById(IZUM).orElseThrow(), publication.forFacility(IZUM));
        assertThat(izum.selectedFieldProvenanceSummary())
                .containsEntry("NAME", MunicipalSourceIdentity.IZUM)
                .doesNotContainKey("TARIFF_ASSIGNMENT")
                .doesNotContainKey("ACCESS");
        assertThat(izum.registryConfidenceOrReviewStatus()).isNull();
        assertThat(mutationCounts()).isEqualTo(before);
    }

    @Test
    void killSwitchFalseRestoresNullProvenanceFields() {
        Map<String, Long> before = mutationCounts();
        assertThat(registryProperties.isProvenancePublicationEnabled()).isTrue();

        MunicipalFacilityResponse on = MunicipalFacilityResponse.from(
                query.findById(IZUM).orElseThrow(), publication.forFacility(IZUM));
        assertThat(on.selectedFieldProvenanceSummary()).isNotEmpty();

        registryProperties.setProvenancePublicationEnabled(false);
        MunicipalFacilityResponse off = MunicipalFacilityResponse.from(
                query.findById(IZUM).orElseThrow(), publication.forFacility(IZUM));
        assertThat(off.contributingSourceKeys()).isNull();
        assertThat(off.selectedFieldProvenanceSummary()).isNull();
        assertThat(off.registryConfidenceOrReviewStatus()).isNull();
        assertThat(registryProperties.isProvenanceIngestWriteEnabled()).isTrue();
        assertThat(mutationCounts()).isEqualTo(before);
    }

    @Test
    void flagOffNearbyAndDetailKeepProvenanceNullAndRegistryUnchanged() {
        registryProperties.setProvenancePublicationEnabled(false);
        Map<String, Long> before = mutationCounts();

        List<MunicipalFacilityQueryService.FacilityView> nearby =
                query.nearby(38.42000, 27.14000, 5000, 10);
        assertThat(nearby).isNotEmpty();

        for (MunicipalFacilityQueryService.FacilityView view : nearby) {
            MunicipalFacilityResponse response =
                    MunicipalFacilityResponse.from(view, publication.forFacility(view.id()));
            assertThat(response.contributingSourceKeys()).isNull();
            assertThat(response.selectedFieldProvenanceSummary()).isNull();
            assertThat(response.registryConfidenceOrReviewStatus()).isNull();
        }

        MunicipalFacilityResponse detail = MunicipalFacilityResponse.from(
                query.findById(IZUM).orElseThrow(), publication.forFacility(IZUM));
        assertThat(detail.selectedFieldProvenanceSummary()).isNull();
        assertThat(mutationCounts()).isEqualTo(before);
        assertThat(registryProperties.isReviewedLinkingEnabled()).isFalse();
        assertThat(registryProperties.isAutomaticLinkingEnabled()).isFalse();
        assertThat(registryProperties.isCandidateGenerationEnabled()).isFalse();
    }

    @Test
    void flagOnEnrichesIzumAndOsmWithoutInternalsOrIzelmanLeak() {
        registryProperties.setProvenancePublicationEnabled(true);
        insertIzumOccupancy();
        Map<String, Long> before = mutationCounts();

        MunicipalFacilityResponse izum = MunicipalFacilityResponse.from(
                query.findById(IZUM).orElseThrow(), publication.forFacility(IZUM));
        MunicipalFacilityResponse osm = MunicipalFacilityResponse.from(
                query.findById(OSM).orElseThrow(), publication.forFacility(OSM));

        assertThat(izum.selectedFieldProvenanceSummary())
                .containsEntry("NAME", MunicipalSourceIdentity.IZUM)
                .containsEntry("COORDINATES", MunicipalSourceIdentity.IZUM)
                .containsEntry("ADDRESS", MunicipalSourceIdentity.IZUM)
                .doesNotContainKey("TARIFF_ASSIGNMENT")
                .doesNotContainKey("ACCESS");
        assertThat(izum.contributingSourceKeys()).containsExactly(MunicipalSourceIdentity.IZUM);
        assertThat(izum.registryConfidenceOrReviewStatus()).isNull();
        assertThat(izum.availableSpaces()).isEqualTo(100);
        assertThat(izum.availabilitySource()).isEqualTo(MunicipalSourceIdentity.IZUM);
        assertThat(izum.selectedFieldProvenanceSummary().values())
                .noneMatch(v -> v.contains(":") || v.contains("REVIEW") || v.contains("CURRENT"));

        assertThat(osm.selectedFieldProvenanceSummary())
                .containsEntry("NAME", MunicipalSourceIdentity.OSM)
                .containsEntry("ATTRIBUTION", MunicipalSourceIdentity.OSM);
        assertThat(osm.contributingSourceKeys()).containsExactly(MunicipalSourceIdentity.OSM);
        assertThat(osm.availableSpaces()).isNull();
        assertThat(osm.availabilitySource()).isNull();

        List<MunicipalFacilityResponse> nearby = query.nearby(38.42000, 27.14000, 5000, 10).stream()
                .map(view -> MunicipalFacilityResponse.from(view, publication.forFacility(view.id())))
                .toList();
        assertThat(nearby).extracting(MunicipalFacilityResponse::id).contains(IZUM, OSM);
        assertThat(nearby).allSatisfy(response -> {
            assertThat(response.selectedFieldProvenanceSummary()).isNotNull();
            assertThat(response.registryConfidenceOrReviewStatus()).isNull();
        });

        assertThat(mutationCounts()).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM municipal_link_candidates")).isZero();
        assertThat(registryProperties.isReviewedLinkingEnabled()).isFalse();
        assertThat(registryProperties.isAutomaticLinkingEnabled()).isFalse();
    }

    @Test
    void nullProvenanceFacilityReturnsEmptyMapsWhenFlagOn() {
        UUID bare = UUID.fromString("00000000-0000-0000-0000-000000009199");
        insertFacility(bare, "Bare Lot", "OFF_STREET", "Konak", 38.43000, 27.15000, 10);
        insertLink(bare, MunicipalSourceIdentity.OSM, "osm-bare-1");
        jdbc.sql("""
                UPDATE municipal_parking_facilities
                SET primary_source_key = :key WHERE id = :id
                """).param("key", MunicipalSourceIdentity.OSM).param("id", bare).update();

        registryProperties.setProvenancePublicationEnabled(true);
        MunicipalFacilityResponse response = MunicipalFacilityResponse.from(
                query.findById(bare).orElseThrow(), publication.forFacility(bare));

        assertThat(response.contributingSourceKeys()).isEmpty();
        assertThat(response.selectedFieldProvenanceSummary()).isEmpty();
        assertThat(response.registryConfidenceOrReviewStatus()).isNull();
    }

    private void insertIzumOccupancy() {
        UUID syncRunId = UUID.fromString("00000000-0000-0000-0000-000000009180");
        jdbc.sql("DELETE FROM municipal_occupancy_snapshots").update();
        jdbc.sql("DELETE FROM municipal_source_sync_runs WHERE id = :id")
                .param("id", syncRunId)
                .update();
        jdbc.sql("""
                INSERT INTO municipal_source_sync_runs(
                  id, source_id, correlation_id, started_at, completed_at, status,
                  records_received, records_accepted, records_rejected, records_inserted,
                  records_updated, records_unchanged, occupancy_inserted)
                SELECT :id, id, 'prov-pub-occ', now(), now(), 'SUCCESS',
                       1, 1, 0, 0, 0, 1, 1
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
                       'SOURCE', 120, 20, 100, 'LIVE', 'occ-hash-prov-pub', now()
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

    private Map<String, Long> mutationCounts() {
        return Map.of(
                "candidates", count("SELECT count(*) FROM municipal_link_candidates"),
                "links", count("SELECT count(*) FROM municipal_facility_source_links"),
                "aliases", count("SELECT count(*) FROM municipal_facility_aliases"),
                "occupancy", count("SELECT count(*) FROM municipal_occupancy_snapshots"),
                "tariffs", count("SELECT count(*) FROM municipal_tariff_assignments"),
                "reviews", count("SELECT count(*) FROM municipal_link_review_audit"),
                "provenance", count("SELECT count(*) FROM municipal_facility_field_provenance"));
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private void insertFacility(
            UUID id, String name, String type, String address, double lat, double lng, int capacity) {
        jdbc.sql("""
                INSERT INTO municipal_parking_facilities (
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
                INSERT INTO municipal_facility_source_links (
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

    private void insertProvenance(UUID facilityId, String field, String sourceKey, String confidence) {
        jdbc.sql("""
                INSERT INTO municipal_facility_field_provenance (
                    id,facility_id,field_name,source_key,source_record_id,source_content_ts,fetch_ts,
                    source_age_class,confidence_or_review_state,selection_reason,last_selected_at,version)
                VALUES (
                    :id,:facility,:field,:sourceKey,:recordId,now(),now(),
                    'CURRENT',:confidence,'test-fixture',now(),0)
                """)
                .param("id", UUID.randomUUID())
                .param("facility", facilityId)
                .param("field", field)
                .param("sourceKey", sourceKey)
                .param("recordId", sourceKey + "-" + field)
                .param("confidence", confidence)
                .update();
    }
}
