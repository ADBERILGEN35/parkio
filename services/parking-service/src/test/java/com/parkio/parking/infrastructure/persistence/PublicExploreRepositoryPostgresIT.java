package com.parkio.parking.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.PublicExploreQueryService;
import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.testsupport.PostgisTestImages;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class PublicExploreRepositoryPostgresIT {
    private static final Set<String> IZUM_KEYS = Set.of(MunicipalSourceIdentity.IZUM);
    private static final Set<String> BOTH_KEYS =
            Set.of(MunicipalSourceIdentity.IZUM, MunicipalSourceIdentity.ISPARK);
    private static final UUID NON_IZUM = UUID.fromString("00000000-0000-0000-0000-000000009101");
    private static final UUID OUTSIDE = UUID.fromString("00000000-0000-0000-0000-000000009102");
    private static final UUID INACTIVE = UUID.fromString("00000000-0000-0000-0000-000000009103");
    private static final UUID UNPUBLISHABLE = UUID.fromString("00000000-0000-0000-0000-000000009104");
    private static final UUID ISPARK_NEAR = UUID.fromString("00000000-0000-0000-0000-000000009105");
    private static final UUID ISPARK_KADIKOY = UUID.fromString("00000000-0000-0000-0000-000000009106");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgisTestImages.dockerImageName())
            .withDatabaseName("parkio_public_explore_it")
            .withUsername("parkio")
            .withPassword("parkio");

    @Autowired MunicipalFacilityRepository facilities;
    @Autowired JdbcClient jdbc;

    private final List<UUID> included = new ArrayList<>();

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
    }

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM municipal_facility_source_links").update();
        jdbc.sql("DELETE FROM municipal_parking_facilities").update();
        included.clear();
        for (int index = 0; index < 22; index++) {
            UUID id = UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", 9200 + index));
            included.add(id);
            insertFacility(id, 38.4237 + index * 0.00001, 27.1428, true, MunicipalSourceIdentity.IZUM);
            insertLink(id, MunicipalSourceIdentity.IZUM, true, "public-in-" + index);
        }

        insertFacility(NON_IZUM, 38.4237, 27.1428, true, MunicipalSourceIdentity.OSM);
        insertLink(NON_IZUM, MunicipalSourceIdentity.OSM, true, "public-non-izum");
        insertFacility(OUTSIDE, 38.5000, 27.1428, true, MunicipalSourceIdentity.IZUM);
        insertLink(OUTSIDE, MunicipalSourceIdentity.IZUM, true, "public-outside");
        insertFacility(INACTIVE, 38.4237, 27.1428, false, MunicipalSourceIdentity.IZUM);
        insertLink(INACTIVE, MunicipalSourceIdentity.IZUM, true, "public-inactive");
        insertFacility(UNPUBLISHABLE, 38.4237, 27.1428, true, MunicipalSourceIdentity.IZUM);
        insertLink(UNPUBLISHABLE, MunicipalSourceIdentity.IZUM, false, "public-unpublishable");

        // ISPARK near Izmir center (mixed-order fixture) and Kadıköy spatial fixture.
        insertFacility(ISPARK_NEAR, 38.42371, 27.14281, true, MunicipalSourceIdentity.ISPARK);
        insertLink(ISPARK_NEAR, MunicipalSourceIdentity.ISPARK, true, "public-ispark-near");
        insertFacility(ISPARK_KADIKOY, 40.9901, 29.0290, true, MunicipalSourceIdentity.ISPARK);
        insertLink(ISPARK_KADIKOY, MunicipalSourceIdentity.ISPARK, true, "public-ispark-kadikoy");
    }

    @Test
    void listIsIzumOnlyGeographicallyBoundedActivePublishableAndHardClampedToSix() {
        var result = facilities.publicExploreNearby(
                PublicExploreQueryService.CENTER_LATITUDE,
                PublicExploreQueryService.CENTER_LONGITUDE,
                PublicExploreQueryService.MAX_RADIUS_METERS,
                500,
                IZUM_KEYS);

        assertThat(result).hasSize(PublicExploreQueryService.MAX_LIMIT);
        assertThat(result).allSatisfy(facility -> {
            assertThat(included).contains(facility.id());
            assertThat(facility.linkedSourceKeys()).containsExactly(MunicipalSourceIdentity.IZUM);
        });
        assertThat(result).extracting(MunicipalFacilityRepository.Facility::id)
                .doesNotContain(NON_IZUM, OUTSIDE, INACTIVE, UNPUBLISHABLE, ISPARK_NEAR, ISPARK_KADIKOY);
        assertThat(facilities.countPublicExploreNearby(
                PublicExploreQueryService.CENTER_LATITUDE,
                PublicExploreQueryService.CENTER_LONGITUDE,
                PublicExploreQueryService.MAX_RADIUS_METERS,
                IZUM_KEYS)).isEqualTo(22L);
    }

    @Test
    void multiProviderInterleavesGloballyAndClampsToSix() {
        var result = facilities.publicExploreNearby(
                PublicExploreQueryService.CENTER_LATITUDE,
                PublicExploreQueryService.CENTER_LONGITUDE,
                PublicExploreQueryService.MAX_RADIUS_METERS,
                500,
                BOTH_KEYS);

        assertThat(result).hasSize(PublicExploreQueryService.MAX_LIMIT);
        assertThat(result).extracting(MunicipalFacilityRepository.Facility::id).contains(ISPARK_NEAR);
        assertThat(result).extracting(MunicipalFacilityRepository.Facility::id)
                .doesNotContain(ISPARK_KADIKOY, NON_IZUM, OUTSIDE, INACTIVE, UNPUBLISHABLE);
        assertThat(facilities.countPublicExploreNearby(
                PublicExploreQueryService.CENTER_LATITUDE,
                PublicExploreQueryService.CENTER_LONGITUDE,
                PublicExploreQueryService.MAX_RADIUS_METERS,
                BOTH_KEYS)).isEqualTo(23L);
    }

    @Test
    void kadikoyOriginReturnsIsparkWithoutIzmirAnchor() {
        var result = facilities.publicExploreNearby(40.9901, 29.0290, 5_000, 6, BOTH_KEYS);
        assertThat(result).extracting(MunicipalFacilityRepository.Facility::id)
                .containsExactly(ISPARK_KADIKOY);
        assertThat(facilities.countPublicExploreNearby(40.9901, 29.0290, 5_000, BOTH_KEYS)).isEqualTo(1L);
        assertThat(facilities.countPublicExploreNearby(40.9901, 29.0290, 5_000, IZUM_KEYS)).isZero();
    }

    @Test
    void emptyAllowlistReturnsNoRowsWithoutSqlInEmpty() {
        assertThat(facilities.publicExploreNearby(
                PublicExploreQueryService.CENTER_LATITUDE,
                PublicExploreQueryService.CENTER_LONGITUDE,
                PublicExploreQueryService.MAX_RADIUS_METERS,
                6,
                Set.of())).isEmpty();
        assertThat(facilities.countPublicExploreNearby(
                PublicExploreQueryService.CENTER_LATITUDE,
                PublicExploreQueryService.CENTER_LONGITUDE,
                PublicExploreQueryService.MAX_RADIUS_METERS,
                Set.of())).isZero();
    }

    @Test
    void detailLookupRemainsScopedButIsNotExposedByPublicHttpContract() {
        for (UUID id : List.of(NON_IZUM, OUTSIDE, INACTIVE, UNPUBLISHABLE, ISPARK_NEAR)) {
            assertThat(facilities.findPublicExploreById(
                    id,
                    PublicExploreQueryService.CENTER_LATITUDE,
                    PublicExploreQueryService.CENTER_LONGITUDE,
                    PublicExploreQueryService.MAX_RADIUS_METERS,
                    IZUM_KEYS)).isEmpty();
        }
        assertThat(facilities.findPublicExploreById(
                included.getFirst(),
                PublicExploreQueryService.CENTER_LATITUDE,
                PublicExploreQueryService.CENTER_LONGITUDE,
                PublicExploreQueryService.MAX_RADIUS_METERS,
                IZUM_KEYS)).isPresent();
        assertThat(facilities.findPublicExploreById(
                ISPARK_NEAR,
                PublicExploreQueryService.CENTER_LATITUDE,
                PublicExploreQueryService.CENTER_LONGITUDE,
                PublicExploreQueryService.MAX_RADIUS_METERS,
                BOTH_KEYS)).isPresent();
    }

    private void insertFacility(
            UUID id, double latitude, double longitude, boolean active, String primarySourceKey) {
        jdbc.sql("""
                INSERT INTO municipal_parking_facilities(
                  id,operator_name,facility_type,access_classification,display_name,address_text,
                  latitude,longitude,location,capacity_total,active,lifecycle_state,
                  primary_source_key,created_at,updated_at)
                VALUES (:id,'IZELMAN A.S.','OFF_STREET','PUBLIC',:name,'Konak, Izmir',
                  :lat,:lng,ST_SetSRID(ST_MakePoint(:lng,:lat),4326)::geography,100,:active,'ACTIVE',
                  :primarySource,now(),now())
                """)
                .param("id", id)
                .param("name", "Public Explore " + id)
                .param("lat", latitude)
                .param("lng", longitude)
                .param("active", active)
                .param("primarySource", primarySourceKey)
                .update();
    }

    private void insertLink(UUID facilityId, String sourceKey, boolean active, String externalId) {
        jdbc.sql("""
                INSERT INTO municipal_facility_source_links(
                  id,facility_id,source_id,external_id,source_name,source_metadata_json,raw_record_hash,
                  first_seen_at,last_seen_at,last_successful_sync_at,active,created_at,updated_at)
                SELECT :id,:facility,id,:external,'Public Explore','{}',:hash,
                  now(),now(),now(),:active,now(),now()
                FROM municipal_data_sources WHERE source_key=:sourceKey
                """)
                .param("id", UUID.randomUUID())
                .param("facility", facilityId)
                .param("external", externalId)
                .param("hash", externalId + "-hash")
                .param("active", active)
                .param("sourceKey", sourceKey)
                .update();
    }
}
