package com.parkio.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;

import com.parkio.user.application.command.CreateProfileCommand;
import com.parkio.user.application.event.UserErasureRequestedEvent;
import com.parkio.user.application.event.UserRegisteredEvent;
import com.parkio.user.application.event.UserSuspendedEvent;
import com.parkio.user.application.event.UserRestoredEvent;
import com.parkio.user.infrastructure.persistence.UserProfileRepositoryAdapter;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real Flyway/PostgreSQL transactions; the spy only installs a barrier after real SQL. */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Timeout(30)
class UserErasureFencePostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.flyway.enabled", () -> "true");
    }
    @Autowired UserApplicationService users;
    @Autowired AccountErasureHandler erasure;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean UserProfileRepositoryAdapter profiles;

    @Test
    void createAfterInitialCheckCannotRecreatePiiAfterCommittedErasureAck() throws Exception {
        UUID user = UUID.randomUUID();
        var checked = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(call -> {
            Object actualSqlResult = call.callRealMethod();
            checked.countDown();
            assertThat(resume.await(10, TimeUnit.SECONDS)).isTrue();
            return actualSqlResult;
        }).when(profiles).existsByAuthUserId(user);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var create = executor.submit(() -> users.createProfile(new CreateProfileCommand(
                    user, user + "@example.test", "Synthetic", null, null)));
            assertThat(checked.await(10, TimeUnit.SECONDS)).isTrue();
            var erase = executor.submit(() -> erasure.handle(request(user)));
            // Red baseline: erase commits/ACKs while create is paused. Green: the DB
            // exposes the blocked advisory lock; ACK cannot commit before admitted create.
            await().atMost(Duration.ofSeconds(5)).until(() -> erase.isDone()
                    || jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted",
                            Long.class) > 0);
            if (erase.isDone()) {
                erase.get(2, TimeUnit.SECONDS);
                assertThat(ackCount(user)).isEqualTo(1);
            } else {
                assertThat(ackCount(user)).isZero();
            }
            resume.countDown();
            var created = create.get(10, TimeUnit.SECONDS);
            erase.get(10, TimeUnit.SECONDS);
            assertThat(profileCount(user)).as("PII after committed erasure ACK").isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM user_preferences WHERE user_profile_id=?",
                    Long.class, created.id())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM user_trust_profiles WHERE user_profile_id=?",
                    Long.class, created.id())).isZero();
            assertThat(ackCount(user)).isEqualTo(1);
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void lateAndDuplicateProfileStatusEventsCannotRecreateErasedRecords() {
        UUID user = UUID.randomUUID();
        var request = request(user);
        erasure.handle(request);
        erasure.handle(request);
        var registered = new UserRegisteredEvent(UUID.randomUUID(), user, user + "@example.test", Instant.now());
        var suspended = new UserSuspendedEvent(UUID.randomUUID(), UUID.randomUUID(), user, UUID.randomUUID(), Instant.now());
        var restored = new UserRestoredEvent(UUID.randomUUID(), UUID.randomUUID(), user, UUID.randomUUID(), Instant.now());
        users.handleUserRegistered(registered);
        users.handleUserRegistered(registered);
        users.handleUserSuspended(suspended);
        users.handleUserSuspended(suspended);
        users.handleUserRestored(restored);
        assertThat(profileCount(user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pending_user_status_events WHERE auth_user_id=?",
                Long.class, user)).isZero();
        assertThat(ackCount(user)).isEqualTo(1);
    }

    @Test
    void activeUserStillProvisionsOnceAndAppliesParkedStatus() {
        UUID user = UUID.randomUUID();
        users.handleUserSuspended(new UserSuspendedEvent(UUID.randomUUID(), UUID.randomUUID(), user,
                UUID.randomUUID(), Instant.now()));
        var event = new UserRegisteredEvent(UUID.randomUUID(), user, user + "@example.test", Instant.now());
        users.handleUserRegistered(event);
        users.handleUserRegistered(event);
        assertThat(profileCount(user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM user_profiles WHERE auth_user_id=?", String.class, user))
                .isEqualTo("SUSPENDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pending_user_status_events WHERE auth_user_id=?",
                Long.class, user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_preferences p JOIN user_profiles u ON p.user_profile_id=u.id WHERE u.auth_user_id=?",
                Long.class, user)).isEqualTo(1);
    }

    private long profileCount(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM user_profiles WHERE auth_user_id=?", Long.class, user);
    }
    private long ackCount(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type='UserErasureAcknowledged' AND payload::jsonb->>'authUserId'=?",
                Long.class, user.toString());
    }
    private static UserErasureRequestedEvent request(UUID user) {
        return new UserErasureRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), user, Instant.now());
    }
}
