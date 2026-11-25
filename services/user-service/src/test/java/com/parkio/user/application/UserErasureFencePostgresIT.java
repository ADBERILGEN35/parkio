package com.parkio.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.parkio.user.application.command.CreateProfileCommand;
import com.parkio.user.application.event.UserErasureRequestedEvent;
import com.parkio.user.application.event.UserRegisteredEvent;
import com.parkio.user.application.event.UserSuspendedEvent;
import com.parkio.user.application.event.UserRestoredEvent;
import com.parkio.user.domain.UserStatus;
import com.parkio.user.domain.exception.UserErrorCode;
import com.parkio.user.domain.exception.UserException;
import com.parkio.user.domain.place.PlaceDestinationSource;
import com.parkio.user.domain.place.RecentParkingTargetKind;
import com.parkio.user.infrastructure.persistence.ErasureAckOutboxAdapter;
import com.parkio.user.infrastructure.persistence.RecentDestinationRepositoryAdapter;
import com.parkio.user.infrastructure.persistence.UserOwnerFenceAdapter;
import com.parkio.user.infrastructure.persistence.UserProfileRepositoryAdapter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real Flyway/PostgreSQL transactions. The spies only pause a call after its real SQL, or give
 * an ordering case's fence waits a longer bound.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Timeout(30)
class UserErasureFencePostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    /**
     * Fence bound for the ordering cases, which leave a call parked on the fence until the test
     * resumes the paused erasure. In a passing run that wait lasts at most the 5 s lock poll plus
     * the 10 s erasure wait, so with a longer bound a runner stall fails one of those waits
     * instead of turning the expected outcome into {@code PROFILE_BUSY}. The stuck-erasure case
     * keeps the configured default.
     */
    private static final Duration ORDERING_FENCE_BOUND = Duration.ofSeconds(20);
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
    @Autowired SavedPlaceApplicationService savedPlaces;
    @Autowired FavouriteDestinationApplicationService favouriteDestinations;
    @Autowired FavouriteParkingApplicationService favouriteParking;
    @Autowired RecentDestinationApplicationService recentDestinations;
    @Autowired RecentParkingApplicationService recentParking;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean UserProfileRepositoryAdapter profiles;
    @MockitoSpyBean ErasureAckOutboxAdapter ackOutbox;
    @MockitoSpyBean RecentDestinationRepositoryAdapter recentDestinationRows;
    @MockitoSpyBean UserOwnerFenceAdapter ownerFence;

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

    /**
     * The opposite interleaving: erasure holds the exclusive fence, with its tombstone and deletes
     * written but not committed, when a create or a late registration/status event arrives.
     */
    @ParameterizedTest
    @ValueSource(strings = {"create", "registered", "suspended"})
    void writerArrivingWhileErasureHoldsFenceWaitsThenCannotRecreateRecords(String writer) throws Exception {
        useOrderingFenceBound();
        UUID user = UUID.randomUUID();
        // A late event targets an existing profile; a create targets a user without one.
        UUID existingProfile = "create".equals(writer) ? null : users.createProfile(new CreateProfileCommand(
                user, user + "@example.test", "Synthetic", null, null)).id();
        var acking = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(call -> {
            acking.countDown();
            assertThat(resume.await(10, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(ackOutbox).append(any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var erase = executor.submit(() -> erasure.handle(request(user)));
            assertThat(acking.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> write = executor.submit(() -> {
                switch (writer) {
                    case "create" -> users.createProfile(new CreateProfileCommand(
                            user, user + "@example.test", "Synthetic", null, null));
                    case "registered" -> users.handleUserRegistered(new UserRegisteredEvent(
                            UUID.randomUUID(), user, user + "@example.test", Instant.now()));
                    default -> users.handleUserSuspended(new UserSuspendedEvent(
                            UUID.randomUUID(), UUID.randomUUID(), user, UUID.randomUUID(), Instant.now()));
                }
                return null;
            });
            await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted", Long.class) > 0);
            assertThat(write.isDone()).as("writer admitted while erasure holds the fence").isFalse();
            resume.countDown();
            erase.get(10, TimeUnit.SECONDS);
            if ("create".equals(writer)) {
                assertThatThrownBy(() -> write.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .cause().isInstanceOfSatisfying(UserException.class,
                                e -> assertThat(e.errorCode()).isEqualTo(UserErrorCode.PROFILE_NOT_FOUND));
            } else {
                write.get(10, TimeUnit.SECONDS);
            }
            assertThat(profileCount(user)).as("PII after committed erasure ACK").isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pending_user_status_events WHERE auth_user_id=?",
                    Long.class, user)).isZero();
            if (existingProfile != null) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM user_preferences WHERE user_profile_id=?",
                        Long.class, existingProfile)).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM user_trust_profiles WHERE user_profile_id=?",
                        Long.class, existingProfile)).isZero();
            }
            assertThat(ackCount(user)).isEqualTo(1);
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * A {@code readOnly} path through {@code requireProfile}: the gateway's status read. H2 skips
     * the advisory lock, so only PostgreSQL shows that the shared lock is taken inside the
     * read-only transaction, waits while erasure holds the exclusive lock, and is followed by a
     * tombstone read that sees the committed erasure.
     */
    @Test
    void readOnlyStatusReadTakesTheSharedLockAndWaitsForErasure() throws Exception {
        UUID user = UUID.randomUUID();
        users.createProfile(new CreateProfileCommand(user, user + "@example.test", "Synthetic", null, null));
        var insideRead = new AtomicReference<String>();
        doAnswer(call -> {
            // Runs on the read's own connection, after requireProfile passed the fence.
            insideRead.set(jdbc.queryForObject("SELECT current_setting('transaction_read_only') || ' '"
                    + " || (SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND mode = 'ShareLock'"
                    + " AND granted AND pid = pg_backend_pid()) || ' ' || current_setting('lock_timeout')",
                    String.class));
            return call.callRealMethod();
        }).when(profiles).findByAuthUserId(user);
        assertThat(users.getAccountStatus(user).status()).isEqualTo(UserStatus.ACTIVE);
        // The fence bounds only its own wait: the session's lock_timeout (0) is back afterwards.
        assertThat(insideRead.get()).as("transaction_read_only, shared fence locks held, lock_timeout")
                .isEqualTo("on 1 0");

        useOrderingFenceBound();
        var acking = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(call -> {
            acking.countDown();
            assertThat(resume.await(10, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(ackOutbox).append(any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var erase = executor.submit(() -> erasure.handle(request(user)));
            assertThat(acking.await(10, TimeUnit.SECONDS)).isTrue();
            var read = executor.submit(() -> users.getAccountStatus(user));
            await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND mode='ShareLock' AND NOT granted",
                    Long.class) > 0);
            assertThat(read.isDone()).as("status read admitted while erasure holds the fence").isFalse();
            resume.countDown();
            erase.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> read.get(10, TimeUnit.SECONDS))
                    .cause().isInstanceOfSatisfying(UserException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(UserErrorCode.PROFILE_NOT_FOUND));
            assertThat(profileCount(user)).isZero();
            assertThat(ackCount(user)).isEqualTo(1);
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * Saved places, favourites and recents resolve the profile under the fence (#597): a child
     * write that arrives while erasure holds the exclusive lock waits on the fence, not on a
     * row lock, and then sees the tombstone instead of writing a row that the erasure has to
     * find through the {@code REFERENCES user_profiles} foreign key.
     */
    @ParameterizedTest
    @ValueSource(strings = {"saved-place", "favourite-destination", "favourite-parking", "recent-destination",
            "recent-parking"})
    void childWriteArrivingWhileErasureHoldsFenceWaitsThenSeesTheTombstone(String path) throws Exception {
        useOrderingFenceBound();
        UUID user = UUID.randomUUID();
        UUID profile = users.createProfile(new CreateProfileCommand(
                user, user + "@example.test", "Synthetic", null, null)).id();
        var acking = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(call -> {
            acking.countDown();
            assertThat(resume.await(10, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(ackOutbox).append(any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var erase = executor.submit(() -> erasure.handle(request(user)));
            assertThat(acking.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> write = executor.submit(() -> childWrite(path, user));
            await().atMost(Duration.ofSeconds(5)).until(() -> write.isDone() || !ungrantedLocks().isEmpty());
            // Without the fence the write commits its row here, and erasure then fails on the
            // foreign key when it deletes the profile.
            assertThat(write.isDone()).as("child write finished while erasure holds the fence").isFalse();
            assertThat(ungrantedLocks()).as("the child write waits on").isEqualTo("advisory:ShareLock");
            resume.countDown();
            erase.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> write.get(10, TimeUnit.SECONDS))
                    .cause().isInstanceOfSatisfying(UserException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(UserErrorCode.PROFILE_NOT_FOUND));
            assertThat(childRowCount(profile)).isZero();
            assertThat(profileCount(user)).isZero();
            assertThat(ackCount(user)).isEqualTo(1);
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * The opposite order: a child write passed the fence and inserted its row, uncommitted, when
     * erasure starts. Erasure waits on the fence until the write commits, then deletes the row.
     * Without the fence, erasure ran ahead, blocked on the row lock that the insert's foreign key
     * check holds on the profile, and failed with a NO ACTION violation once the write committed,
     * so the erasure needed a Kafka redelivery.
     */
    @Test
    void childWriteAdmittedBeforeErasureCommitsFirstAndErasureStillCompletes() throws Exception {
        UUID user = UUID.randomUUID();
        UUID profile = users.createProfile(new CreateProfileCommand(
                user, user + "@example.test", "Synthetic", null, null)).id();
        var inserted = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(call -> {
            // confirm() prunes after saving; this query auto-flushes the INSERT first.
            Object actualSqlResult = call.callRealMethod();
            inserted.countDown();
            assertThat(resume.await(10, TimeUnit.SECONDS)).isTrue();
            return actualSqlResult;
        }).when(recentDestinationRows).findAllByUserProfileIdOrderByLastUsedAtDesc(profile);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> write = executor.submit(() -> childWrite("recent-destination", user));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            var erase = executor.submit(() -> erasure.handle(request(user)));
            await().atMost(Duration.ofSeconds(5)).until(() -> erase.isDone() || !ungrantedLocks().isEmpty());
            assertThat(ungrantedLocks()).as("erasure waits on").isEqualTo("advisory:ExclusiveLock");
            assertThat(ackCount(user)).isZero();
            resume.countDown();
            write.get(10, TimeUnit.SECONDS);
            erase.get(10, TimeUnit.SECONDS);
            assertThat(childRowCount(profile)).isZero();
            assertThat(profileCount(user)).isZero();
            assertThat(ackCount(user)).isEqualTo(1);
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * A stuck erasure holds the exclusive lock and does not finish. Fenced callers give up at the
     * fence's lock timeout (2 s by default) with {@code PROFILE_BUSY}, which the API maps to 503
     * and a Kafka listener rethrows for redelivery, instead of holding their pooled connections
     * until the erasure ends. The erasure itself is not bounded and still completes afterwards.
     */
    @Test
    void fenceWaitsBehindAStuckErasureEndAtTheLockTimeout() throws Exception {
        UUID user = UUID.randomUUID();
        UUID profile = users.createProfile(new CreateProfileCommand(
                user, user + "@example.test", "Synthetic", null, null)).id();
        var registration = new UserRegisteredEvent(UUID.randomUUID(), user, user + "@example.test", Instant.now());
        var acking = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(call -> {
            acking.countDown();
            assertThat(resume.await(20, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(ackOutbox).append(any());
        var executor = Executors.newFixedThreadPool(4);
        try {
            var erase = executor.submit(() -> erasure.handle(request(user)));
            assertThat(acking.await(10, TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();
            List<Future<?>> waiters = List.of(
                    executor.submit(() -> users.getAccountStatus(user)),
                    executor.submit(() -> childWrite("favourite-destination", user)),
                    executor.submit(() -> {
                        users.handleUserRegistered(registration);
                        return null;
                    }));
            for (Future<?> waiter : waiters) {
                assertThatThrownBy(() -> waiter.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .cause().isInstanceOfSatisfying(UserException.class,
                                e -> assertThat(e.errorCode()).isEqualTo(UserErrorCode.PROFILE_BUSY));
            }
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                    .isBetween(Duration.ofMillis(1900), Duration.ofSeconds(6));
            assertThat(ungrantedLocks()).as("lock requests still waiting after the timeout").isEmpty();
            // The event's transaction rolled back with its inbox claim, so a redelivery processes it.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE id = ?",
                    Long.class, registration.eventId())).isZero();
            assertThat(erase.isDone()).isFalse();
            resume.countDown();
            erase.get(10, TimeUnit.SECONDS);
            assertThat(profileCount(user)).isZero();
            assertThat(childRowCount(profile)).isZero();
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

    /**
     * Gives this test's fence waits {@link #ORDERING_FENCE_BOUND}. The adapter is the same class
     * on the same {@code JdbcTemplate}, so it joins the caller's transaction and runs the same
     * SQL; only the bound differs. The spy is reset after each test.
     */
    private void useOrderingFenceBound() {
        var fence = new UserOwnerFenceAdapter(jdbc, ORDERING_FENCE_BOUND);
        doAnswer(call -> fence.admitWrite(call.getArgument(0))).when(ownerFence).admitWrite(any());
    }

    private Object childWrite(String path, UUID user) {
        UUID facility = UUID.randomUUID();
        return switch (path) {
            case "saved-place" -> savedPlaces.upsertWork(
                    user, 41.01, 28.97, "Synthetic work", PlaceDestinationSource.MAP_PIN, null, null);
            case "favourite-destination" -> favouriteDestinations.add(
                    user, "Synthetic favourite", 41.01, 28.97, PlaceDestinationSource.MAP_PIN, null, null);
            case "favourite-parking" -> favouriteParking.addMunicipalFacility(user, facility);
            case "recent-destination" -> recentDestinations.confirm(
                    user, "Synthetic recent", 41.01, 28.97, PlaceDestinationSource.MAP_PIN, null, null);
            case "recent-parking" -> recentParking.record(user, RecentParkingTargetKind.MUNICIPAL_FACILITY, facility);
            default -> throw new IllegalArgumentException(path);
        };
    }

    /** Lock requests that are waiting, as {@code locktype:mode}; empty when nothing waits. */
    private String ungrantedLocks() {
        return jdbc.queryForObject("SELECT coalesce(string_agg(locktype || ':' || mode, ',' ORDER BY locktype, mode),"
                + " '') FROM pg_locks WHERE NOT granted", String.class);
    }

    private long childRowCount(UUID profile) {
        return jdbc.queryForObject("SELECT (SELECT count(*) FROM saved_places WHERE user_profile_id = ?)"
                + " + (SELECT count(*) FROM favourite_destinations WHERE user_profile_id = ?)"
                + " + (SELECT count(*) FROM favourite_parking WHERE user_profile_id = ?)"
                + " + (SELECT count(*) FROM recent_destinations WHERE user_profile_id = ?)"
                + " + (SELECT count(*) FROM recent_parking WHERE user_profile_id = ?)",
                Long.class, profile, profile, profile, profile, profile);
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
