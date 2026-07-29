package com.parkio.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.analytics.application.event.ParkingHistoryDeletedEvent;
import com.parkio.analytics.application.event.ParkingHistoryDeletionScope;
import com.parkio.analytics.application.event.ParkingSessionCancelledEvent;
import com.parkio.analytics.application.event.ParkingSessionCompletedEvent;
import com.parkio.analytics.application.event.ParkingSessionStartedEvent;
import com.parkio.analytics.application.event.ParkingSpotClaimedEvent;
import com.parkio.analytics.application.port.AnalyticsEventRepository;
import com.parkio.analytics.application.port.DailyAnalyticsSnapshotRepository;
import com.parkio.analytics.application.port.InboxEventRepository;
import com.parkio.analytics.application.port.ParkingAnalyticsSnapshotRepository;
import com.parkio.analytics.application.port.UserAnalyticsSnapshotRepository;
import com.parkio.analytics.domain.AnalyticsEvent;
import com.parkio.analytics.domain.AnalyticsMetricType;
import com.parkio.analytics.domain.DailyAnalyticsSnapshot;
import com.parkio.analytics.domain.ParkingAnalyticsSnapshot;
import com.parkio.analytics.domain.UserAnalyticsSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Ingestion + inbox dedup + privacy-field absence for ParkingSession lifecycle events. */
class ParkingSessionLifecycleIngestionTest {

    private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");
    private static final Instant ENDED = Instant.parse("2026-07-24T12:30:00Z");

    private FakeAnalyticsEventRepository events;
    private FakeInboxRepository inbox;
    private AnalyticsApplicationService service;

    @BeforeEach
    void setUp() {
        events = new FakeAnalyticsEventRepository();
        FakeDailyRepository daily = new FakeDailyRepository();
        FakeUserRepository users = new FakeUserRepository();
        FakeParkingRepository parking = new FakeParkingRepository();
        inbox = new FakeInboxRepository();
        service = new AnalyticsApplicationService(
                events, daily, users, parking, inbox, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void manualStartedCreatesOneObservation() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        service.handleParkingSessionStarted(new ParkingSessionStartedEvent(
                eventId, sessionId, userId, "ACTIVE", "MANUAL", NOW, NOW));

        assertThat(events.all).hasSize(1);
        AnalyticsEvent row = events.all.get(0);
        assertThat(row.sourceEventId()).isEqualTo(eventId);
        assertThat(row.metricType()).isEqualTo(AnalyticsMetricType.PARKING_SESSION_STARTED_MANUAL);
        assertThat(row.userId()).isEqualTo(userId);
        assertThat(row.relatedEntityId()).isEqualTo(sessionId);
        assertThat(row.value()).isZero();
        assertThat(row.occurredAt()).isEqualTo(NOW);
        assertPrivacyMinimized(row);
    }

    @Test
    void communityStartedUsesDistinctMetricFromSpotClaim() {
        UUID sessionEventId = UUID.randomUUID();
        UUID claimEventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        service.handleParkingSessionStarted(new ParkingSessionStartedEvent(
                sessionEventId, UUID.randomUUID(), userId, "ACTIVE", "COMMUNITY", NOW, NOW));
        service.handleParkingSpotClaimed(new ParkingSpotClaimedEvent(
                claimEventId, UUID.randomUUID(), UUID.randomUUID(), userId, NOW));

        assertThat(events.all).hasSize(2);
        assertThat(events.all)
                .extracting(AnalyticsEvent::metricType)
                .containsExactlyInAnyOrder(
                        AnalyticsMetricType.PARKING_SESSION_STARTED_COMMUNITY,
                        AnalyticsMetricType.PARKING_CLAIMED);
        assertThat(ParkingSessionLifecycleMapper.canonicalName("ParkingSessionStarted"))
                .isEqualTo("parking_session_started");
    }

    @Test
    void completedStoresDurationSeconds() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        service.handleParkingSessionCompleted(new ParkingSessionCompletedEvent(
                eventId, sessionId, UUID.randomUUID(), "COMPLETED", "MANUAL", NOW, ENDED, ENDED));

        assertThat(events.all).hasSize(1);
        assertThat(events.all.get(0).metricType()).isEqualTo(AnalyticsMetricType.PARKING_SESSION_COMPLETED);
        assertThat(events.all.get(0).value()).isEqualTo(1800L);
        assertThat(events.all.get(0).relatedEntityId()).isEqualTo(sessionId);
        assertPrivacyMinimized(events.all.get(0));
    }

    @Test
    void cancelledStoresDurationSeconds() {
        service.handleParkingSessionCancelled(new ParkingSessionCancelledEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "CANCELLED", "AUTO", NOW, ENDED, ENDED));

        assertThat(events.all).hasSize(1);
        assertThat(events.all.get(0).metricType()).isEqualTo(AnalyticsMetricType.PARKING_SESSION_CANCELLED);
        assertThat(events.all.get(0).value()).isEqualTo(1800L);
    }

    @Test
    void duplicateEventIdIsNoOp() {
        ParkingSessionStartedEvent event = new ParkingSessionStartedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "ACTIVE", "MANUAL", NOW, NOW);

        service.handleParkingSessionStarted(event);
        service.handleParkingSessionStarted(event);

        assertThat(events.all).hasSize(1);
        assertThat(inbox.claimed).hasSize(1);
    }

    @Test
    void terminalWithoutPriorStartedIsAcceptedIndependently() {
        service.handleParkingSessionCompleted(new ParkingSessionCompletedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "COMPLETED", "MANUAL", NOW, ENDED, ENDED));

        assertThat(events.all).hasSize(1);
        assertThat(events.all.get(0).metricType()).isEqualTo(AnalyticsMetricType.PARKING_SESSION_COMPLETED);
    }

    @Test
    void delayedStartedDoesNotOverwriteTerminal() {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        service.handleParkingSessionCompleted(new ParkingSessionCompletedEvent(
                UUID.randomUUID(), sessionId, userId, "COMPLETED", "MANUAL", NOW, ENDED, ENDED));
        service.handleParkingSessionStarted(new ParkingSessionStartedEvent(
                UUID.randomUUID(), sessionId, userId, "ACTIVE", "MANUAL", NOW, NOW));

        assertThat(events.all).hasSize(2);
        assertThat(events.all)
                .extracting(AnalyticsEvent::metricType)
                .containsExactly(
                        AnalyticsMetricType.PARKING_SESSION_COMPLETED,
                        AnalyticsMetricType.PARKING_SESSION_STARTED_MANUAL);
    }

    @Test
    void conflictingTerminalFactsAreBothPersistedIndependently() {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        service.handleParkingSessionCompleted(new ParkingSessionCompletedEvent(
                UUID.randomUUID(), sessionId, userId, "COMPLETED", "MANUAL", NOW, ENDED, ENDED));
        service.handleParkingSessionCancelled(new ParkingSessionCancelledEvent(
                UUID.randomUUID(), sessionId, userId, "CANCELLED", "MANUAL", NOW, ENDED, ENDED));

        assertThat(events.all).hasSize(2);
    }

    @Test
    void zeroDurationIsAllowed() {
        service.handleParkingSessionCompleted(new ParkingSessionCompletedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "COMPLETED", "MANUAL", NOW, NOW, NOW));

        assertThat(events.all.get(0).value()).isZero();
    }

    @Test
    void sessionMetricsAreQueryableViaGetMetrics() {
        service.handleParkingSessionStarted(new ParkingSessionStartedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "ACTIVE", "MANUAL", NOW, NOW));

        assertThat(service.getMetrics())
                .anySatisfy(metric -> {
                    assertThat(metric.metricType())
                            .isEqualTo(AnalyticsMetricType.PARKING_SESSION_STARTED_MANUAL);
                    assertThat(metric.totalCount()).isEqualTo(1);
                });
    }

    @Test
    void singleHistoryDeletedStoresDeletedCountOne() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        service.handleParkingHistoryDeleted(new ParkingHistoryDeletedEvent(
                eventId, userId, ParkingHistoryDeletionScope.SINGLE_TERMINAL_SESSION, sessionId, 1, NOW));

        assertThat(events.all).hasSize(1);
        AnalyticsEvent row = events.all.get(0);
        assertThat(row.metricType()).isEqualTo(AnalyticsMetricType.PARKING_SESSION_HISTORY_DELETED);
        assertThat(row.userId()).isEqualTo(userId);
        assertThat(row.relatedEntityId()).isEqualTo(sessionId);
        assertThat(row.value()).isEqualTo(1L);
        assertPrivacyMinimized(row);
    }

    @Test
    void bulkHistoryDeletedStoresDeletedCount() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        service.handleParkingHistoryDeleted(new ParkingHistoryDeletedEvent(
                eventId, userId, ParkingHistoryDeletionScope.ALL_TERMINAL_HISTORY, null, 4, NOW));

        assertThat(events.all).hasSize(1);
        AnalyticsEvent row = events.all.get(0);
        assertThat(row.metricType()).isEqualTo(AnalyticsMetricType.PARKING_SESSION_HISTORY_DELETED);
        assertThat(row.relatedEntityId()).isEqualTo(userId);
        assertThat(row.value()).isEqualTo(4L);
    }

    @Test
    void duplicateHistoryDeletedEventIdIsNoOp() {
        ParkingHistoryDeletedEvent event = new ParkingHistoryDeletedEvent(
                UUID.randomUUID(), UUID.randomUUID(), ParkingHistoryDeletionScope.SINGLE_TERMINAL_SESSION,
                UUID.randomUUID(), 1, NOW);

        service.handleParkingHistoryDeleted(event);
        service.handleParkingHistoryDeleted(event);

        assertThat(events.all).hasSize(1);
        assertThat(inbox.claimed).hasSize(1);
    }

    private static void assertPrivacyMinimized(AnalyticsEvent row) {
        // Persistence model has no coordinate / idempotency / profile fields.
        assertThat(row.getClass().getDeclaredFields())
                .extracting(f -> f.getName())
                .doesNotContain(
                        "latitude", "longitude", "location", "geohash", "address", "spotId",
                        "idempotencyKey", "email", "displayName", "deviceId", "payload");
    }

    private static final class FakeAnalyticsEventRepository implements AnalyticsEventRepository {
        private final List<AnalyticsEvent> all = new ArrayList<>();

        @Override
        public AnalyticsEvent save(AnalyticsEvent event) {
            all.add(event);
            return event;
        }
    }

    private static final class FakeDailyRepository implements DailyAnalyticsSnapshotRepository {
        private final Map<String, DailyAnalyticsSnapshot> byKey = new HashMap<>();

        @Override
        public DailyAnalyticsSnapshot save(DailyAnalyticsSnapshot snapshot) {
            byKey.put(snapshot.snapshotDate() + "|" + snapshot.metricType(), snapshot);
            return snapshot;
        }

        @Override
        public Optional<DailyAnalyticsSnapshot> findByDateAndMetricType(LocalDate date, AnalyticsMetricType type) {
            return Optional.ofNullable(byKey.get(date + "|" + type));
        }

        @Override
        public List<DailyAnalyticsSnapshot> findAll() {
            return new ArrayList<>(byKey.values());
        }
    }

    private static final class FakeUserRepository implements UserAnalyticsSnapshotRepository {
        private final Map<String, UserAnalyticsSnapshot> byKey = new HashMap<>();

        @Override
        public UserAnalyticsSnapshot save(UserAnalyticsSnapshot snapshot) {
            byKey.put(snapshot.userId() + "|" + snapshot.metricType(), snapshot);
            return snapshot;
        }

        @Override
        public Optional<UserAnalyticsSnapshot> findByUserIdAndMetricType(UUID userId, AnalyticsMetricType type) {
            return Optional.ofNullable(byKey.get(userId + "|" + type));
        }

        @Override
        public List<UserAnalyticsSnapshot> findByUserId(UUID userId) {
            return byKey.values().stream().filter(s -> s.userId().equals(userId)).toList();
        }
    }

    private static final class FakeParkingRepository implements ParkingAnalyticsSnapshotRepository {
        private final Map<AnalyticsMetricType, ParkingAnalyticsSnapshot> byType = new HashMap<>();

        @Override
        public ParkingAnalyticsSnapshot save(ParkingAnalyticsSnapshot snapshot) {
            byType.put(snapshot.metricType(), snapshot);
            return snapshot;
        }

        @Override
        public Optional<ParkingAnalyticsSnapshot> findByMetricType(AnalyticsMetricType type) {
            return Optional.ofNullable(byType.get(type));
        }

        @Override
        public List<ParkingAnalyticsSnapshot> findAll() {
            return new ArrayList<>(byType.values());
        }
    }

    private static final class FakeInboxRepository implements InboxEventRepository {
        private final java.util.Set<UUID> claimed = java.util.concurrent.ConcurrentHashMap.newKeySet();

        @Override
        public boolean tryClaim(UUID eventId, String eventType, Instant processedAt) {
            return claimed.add(eventId);
        }
    }
}