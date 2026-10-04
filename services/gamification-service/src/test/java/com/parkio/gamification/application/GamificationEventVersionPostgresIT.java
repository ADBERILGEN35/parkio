package com.parkio.gamification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.gamification.application.event.ParkingSpotClaimedEvent;
import com.parkio.gamification.application.event.ParkingSpotRejectedByModeratorEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * U12 / CX-F07: each points, level and trust event carries the row version that its change
 * produced, read back from real Hibernate optimistic-lock versions on PostgreSQL. Projections
 * order snapshots by it. Synthetic users only.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class GamificationEventVersionPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired private GamificationApplicationService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void pointsAndLevelEventsCarryTheProgressVersionOfTheirChange() throws Exception {
        UUID owner = UUID.randomUUID();
        // Four claims at 30 points each: 30, 60, 90, 120. The fourth crosses into level 2.
        for (int i = 0; i < 4; i++) {
            service.handleParkingSpotClaimed(new ParkingSpotClaimedEvent(
                    UUID.randomUUID(), UUID.randomUUID(), owner, UUID.randomUUID(), Instant.now()));
        }

        assertThat(versions("PointsEarned", owner)).containsExactly(0L, 1L, 2L, 3L);
        assertThat(versions("UserLevelChanged", owner)).containsExactly(3L);
        assertThat(jdbc.queryForObject("SELECT version FROM user_level_progress WHERE user_id = ?",
                Long.class, owner)).isEqualTo(3L);
    }

    @Test
    void trustEventsCarryTheTrustScoreVersionOfTheirChange() throws Exception {
        UUID owner = UUID.randomUUID();
        service.handleParkingSpotRejectedByModerator(new ParkingSpotRejectedByModeratorEvent(
                UUID.randomUUID(), UUID.randomUUID(), owner, UUID.randomUUID(), UUID.randomUUID(),
                "ILLEGAL_OR_RISKY", Instant.now()));
        service.handleParkingSpotClaimed(new ParkingSpotClaimedEvent(
                UUID.randomUUID(), UUID.randomUUID(), owner, UUID.randomUUID(), Instant.now()));

        assertThat(versions("TrustScoreUpdated", owner)).containsExactly(0L, 1L);
        // The rejection's deduction and the claim's award are two changes to the points row too.
        assertThat(versions("PointsDeducted", owner)).containsExactly(0L);
        assertThat(versions("PointsEarned", owner)).containsExactly(1L);
    }

    /** {@code aggregateVersion} of the user's outbox events of one type, in commit order. */
    private List<Long> versions(String eventType, UUID user) throws Exception {
        List<String> payloads = jdbc.queryForList("""
                SELECT payload FROM outbox_events
                WHERE event_type = ? AND aggregate_id = ?
                ORDER BY created_at, occurred_at""", String.class, eventType, user);
        List<Long> versions = new ArrayList<>();
        for (String payload : payloads) {
            versions.add(objectMapper.readTree(payload).get("aggregateVersion").asLong());
        }
        return versions;
    }
}
