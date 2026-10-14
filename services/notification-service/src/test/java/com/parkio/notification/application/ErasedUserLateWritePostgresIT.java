package com.parkio.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.notification.application.event.PointsEarnedEvent;
import com.parkio.notification.application.event.UserErasureRequestedEvent;
import java.time.Instant;
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

@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ErasedUserLateWritePostgresIT {

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

    @Autowired private NotificationApplicationService service;
    @Autowired private AccountErasureHandler erasure;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void latePointsEarnedForErasedUserIsConsumedWithoutRecreatingNotifications() {
        UUID erased = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-14T12:00:00Z");

        erasure.handle(new UserErasureRequestedEvent(
                UUID.randomUUID(), UUID.randomUUID(), erased, now));

        UUID lateEventId = UUID.randomUUID();
        service.handlePointsEarned(new PointsEarnedEvent(
                lateEventId, erased, 10L, "UPLOAD", 10L, null, now));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_events WHERE id = ?", Long.class, lateEventId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ?", Long.class, erased))
                .isZero();

        UUID bystanderEvent = UUID.randomUUID();
        service.handlePointsEarned(new PointsEarnedEvent(
                bystanderEvent, bystander, 5L, "UPLOAD", 5L, null, now));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ?", Long.class, bystander))
                .isEqualTo(1L);
    }
}
