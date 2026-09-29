package com.parkio.parking.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.PublicExploreQueryService;
import com.parkio.parking.application.port.ParkingSpotRepository;
import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ParkingContext;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.VehicleType;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.testsupport.PostgisTestImages;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * G06A: with fixed municipal rows, varying protected community spots must not change
 * anonymous Explore municipal totals or produce a community count signal.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Transactional
class Pa06PublicExploreCommunityIsolationPostgresIT {
    private static final UUID MUNICIPAL_A = UUID.fromString("00000000-0000-0000-0000-000000009201");
    private static final UUID MUNICIPAL_B = UUID.fromString("00000000-0000-0000-0000-000000009202");
    private static final double LAT = PublicExploreQueryService.CENTER_LATITUDE;
    private static final double LNG = PublicExploreQueryService.CENTER_LONGITUDE;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgisTestImages.dockerImageName())
            .withDatabaseName("parkio_pa06_isolation_it")
            .withUsername("parkio")
            .withPassword("parkio");

    @Autowired PublicExploreQueryService explore;
    @Autowired ParkingSpotRepository spots;
    @Autowired EntityManager entityManager;
    @Autowired JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("parkio.public-explore.enabled", () -> "true");
        registry.add("parkio.public-explore.allowed-source-families", () -> "izum");
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
    void seedMunicipalOnly() {
        jdbc.sql("DELETE FROM parking_spots").update();
        jdbc.sql("DELETE FROM municipal_facility_source_links").update();
        jdbc.sql("DELETE FROM municipal_parking_facilities").update();
        insertFacility(MUNICIPAL_A, LAT, LNG);
        insertLink(MUNICIPAL_A, "pa06-muni-a");
        insertFacility(MUNICIPAL_B, LAT + 0.00002, LNG);
        insertLink(MUNICIPAL_B, "pa06-muni-b");
    }

    @Test
    void communitySpotPopulationChangesDoNotAlterDisclosedExploreEnvelope() {
        PublicExploreQueryService.DiscoveryResult emptyCommunity = discover();
        assertEnvelope(emptyCommunity, 2L);
        assertThat(spots.countNearbyVisible(LAT, LNG, 5_000)).isZero();

        // Sparse (below former k=3).
        saveVisibleCommunity(LAT + 0.0001, LNG);
        saveVisibleCommunity(LAT + 0.0002, LNG);
        entityManager.flush();
        PublicExploreQueryService.DiscoveryResult sparse = discover();
        assertEnvelope(sparse, 2L);
        assertThat(spots.countNearbyVisible(LAT, LNG, 5_000)).isEqualTo(2L);

        // Former threshold boundary (k=3) and one addition.
        saveVisibleCommunity(LAT + 0.0003, LNG);
        entityManager.flush();
        PublicExploreQueryService.DiscoveryResult atThreshold = discover();
        assertEnvelope(atThreshold, 2L);
        assertThat(spots.countNearbyVisible(LAT, LNG, 5_000)).isEqualTo(3L);

        saveVisibleCommunity(LAT + 0.0004, LNG);
        entityManager.flush();
        PublicExploreQueryService.DiscoveryResult above = discover();
        assertEnvelope(above, 2L);
        assertThat(spots.countNearbyVisible(LAT, LNG, 5_000)).isEqualTo(4L);

        // Removal of one protected record.
        jdbc.sql("DELETE FROM parking_spots").update();
        entityManager.clear();
        PublicExploreQueryService.DiscoveryResult afterClear = discover();
        assertEnvelope(afterClear, 2L);
        assertThat(spots.countNearbyVisible(LAT, LNG, 5_000)).isZero();

        // Overlapping / adjacent geometry still withhold + same municipal total.
        List<PublicExploreQueryService.DiscoveryResult> variants = new ArrayList<>();
        variants.add(explore.discover(new PublicExploreQueryService.DiscoveryQuery(LAT, LNG, 99, null)));
        variants.add(explore.discover(new PublicExploreQueryService.DiscoveryQuery(LAT, LNG, 101, null)));
        variants.add(explore.discover(new PublicExploreQueryService.DiscoveryQuery(LAT + 0.00001, LNG, 100, null)));
        for (var variant : variants) {
            assertThat(variant.communitySpotCountInScope()).isNull();
            assertThat(variant.municipalTotalInScope()).isEqualTo(2L);
        }
    }

    private PublicExploreQueryService.DiscoveryResult discover() {
        return explore.discover(new PublicExploreQueryService.DiscoveryQuery(LAT, LNG, 5_000, null));
    }

    private static void assertEnvelope(PublicExploreQueryService.DiscoveryResult result, long municipalTotal) {
        assertThat(result.communitySpotCountInScope()).isNull();
        assertThat(result.municipalTotalInScope()).isEqualTo(municipalTotal);
        assertThat(result.facilities()).hasSize((int) Math.min(municipalTotal, PublicExploreQueryService.MAX_LIMIT));
        assertThat(result.municipalHiddenCount())
                .isEqualTo(Math.max(municipalTotal - result.facilities().size(), 0L));
    }

    private ParkingSpot saveVisibleCommunity(double latitude, double longitude) {
        Instant now = Instant.now();
        return spots.save(new ParkingSpot(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                latitude,
                longitude,
                null,
                null,
                false,
                Set.of(VehicleType.SEDAN),
                ParkingContext.STREET_PARKING,
                LegalStatus.LEGAL,
                Set.of(),
                ParkingSpotStatus.ACTIVE,
                1.0,
                0,
                0,
                now.plus(2, ChronoUnit.HOURS),
                now,
                now,
                null,
                now,
                now.plus(java.time.Duration.ofHours(24)),
                0,
                now,
                null,
                null));
    }

    private void insertFacility(UUID id, double latitude, double longitude) {
        jdbc.sql("""
                INSERT INTO municipal_parking_facilities(
                  id,operator_name,facility_type,access_classification,display_name,address_text,
                  latitude,longitude,location,capacity_total,active,lifecycle_state,
                  primary_source_key,created_at,updated_at)
                VALUES (:id,'IZELMAN A.S.','OFF_STREET','PUBLIC',:name,'Konak, Izmir',
                  :lat,:lng,ST_SetSRID(ST_MakePoint(:lng,:lat),4326)::geography,100,true,'ACTIVE',
                  :primarySource,now(),now())
                """)
                .param("id", id)
                .param("name", "PA06 Municipal " + id)
                .param("lat", latitude)
                .param("lng", longitude)
                .param("primarySource", MunicipalSourceIdentity.IZUM)
                .update();
    }

    private void insertLink(UUID facilityId, String externalId) {
        jdbc.sql("""
                INSERT INTO municipal_facility_source_links(
                  id,facility_id,source_id,external_id,source_name,source_metadata_json,raw_record_hash,
                  first_seen_at,last_seen_at,last_successful_sync_at,active,created_at,updated_at)
                SELECT :id,:facility,id,:external,'PA06','{}',:hash,
                  now(),now(),now(),true,now(),now()
                FROM municipal_data_sources WHERE source_key=:sourceKey
                """)
                .param("id", UUID.randomUUID())
                .param("facility", facilityId)
                .param("external", externalId)
                .param("hash", externalId + "-hash")
                .param("sourceKey", MunicipalSourceIdentity.IZUM)
                .update();
    }
}
