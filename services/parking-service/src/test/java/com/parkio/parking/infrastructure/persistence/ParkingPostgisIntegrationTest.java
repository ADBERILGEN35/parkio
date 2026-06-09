package com.parkio.parking.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.port.ParkingSpotRepository;
import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ParkingContext;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.VehicleType;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Transactional
class ParkingPostgisIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("parkio_parking_it")
            .withUsername("parkio")
            .withPassword("parkio");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGIS::getUsername);
        registry.add("spring.datasource.password", POSTGIS::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGIS::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("parkio.kafka.provision-topics", () -> "false");
        registry.add("parkio.kafka.relay.enabled", () -> "false");
        registry.add("parkio.kafka.moderation-consumer.enabled", () -> "false");
        registry.add("parkio.lifecycle.parking-expiry.enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
    }

    @Autowired
    private ParkingSpotRepository spots;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearSpots() {
        jdbc.update("DELETE FROM parking_spots");
    }

    @Test
    void flywayCreatesPostgisTriggerAndGistIndex() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class))
                .isGreaterThanOrEqualTo(11);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'postgis'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM pg_trigger
                WHERE tgname = 'trg_parking_spots_sync_location' AND NOT tgisinternal
                """,
                Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'parking_spots'
                  AND indexname = 'idx_parking_spots_location'
                """,
                String.class))
                .containsIgnoringCase("USING gist");
    }

    @Test
    void locationTriggerAndNearbyQueryUseProductionPostgisBehavior() {
        Instant now = Instant.now();
        ParkingSpot nearest = saveSpot(41.0082, 28.9784, ParkingSpotStatus.ACTIVE,
                LegalStatus.LEGAL, now.plus(1, ChronoUnit.HOURS));
        ParkingSpot farther = saveSpot(41.0112, 28.9784, ParkingSpotStatus.VERIFIED,
                LegalStatus.LEGAL, now.plus(1, ChronoUnit.HOURS));
        saveSpot(41.0382, 28.9784, ParkingSpotStatus.ACTIVE,
                LegalStatus.LEGAL, now.plus(1, ChronoUnit.HOURS));
        saveSpot(41.0083, 28.9784, ParkingSpotStatus.ACTIVE,
                LegalStatus.LEGAL, now.minus(1, ChronoUnit.MINUTES));
        saveSpot(41.0084, 28.9784, ParkingSpotStatus.FILLED,
                LegalStatus.LEGAL, now.plus(1, ChronoUnit.HOURS));
        saveSpot(41.0085, 28.9784, ParkingSpotStatus.REJECTED,
                LegalStatus.LEGAL, now.plus(1, ChronoUnit.HOURS));
        saveSpot(41.0086, 28.9784, ParkingSpotStatus.ACTIVE,
                LegalStatus.ILLEGAL_OR_RISKY, now.plus(1, ChronoUnit.HOURS));
        entityManager.flush();
        entityManager.clear();

        var coordinates = jdbc.queryForMap(
                """
                SELECT ST_Y(location::geometry) AS latitude,
                       ST_X(location::geometry) AS longitude
                FROM parking_spots WHERE id = ?
                """,
                nearest.id());
        assertThat(((Number) coordinates.get("latitude")).doubleValue()).isEqualTo(41.0082);
        assertThat(((Number) coordinates.get("longitude")).doubleValue()).isEqualTo(28.9784);

        List<ParkingSpot> results = spots.findNearby(41.0082, 28.9784, 1_000, 20);

        assertThat(results).extracting(ParkingSpot::id)
                .containsExactly(nearest.id(), farther.id());
    }

    private ParkingSpot saveSpot(
            double latitude,
            double longitude,
            ParkingSpotStatus status,
            LegalStatus legalStatus,
            Instant expiresAt) {
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
                legalStatus,
                Set.of(),
                status,
                1.0,
                0,
                0,
                expiresAt,
                now,
                now,
                null));
    }
}
