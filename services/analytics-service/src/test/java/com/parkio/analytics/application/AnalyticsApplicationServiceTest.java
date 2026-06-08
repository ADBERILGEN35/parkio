package com.parkio.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.analytics.application.event.NotificationCreatedEvent;
import com.parkio.analytics.application.event.ParkingSpotClaimedEvent;
import com.parkio.analytics.application.event.ParkingSpotCreatedEvent;
import com.parkio.analytics.application.event.ParkingSpotRejectedEvent;
import com.parkio.analytics.application.event.ParkingSpotVerifiedEvent;
import com.parkio.analytics.application.event.PointsEarnedEvent;
import com.parkio.analytics.application.event.UserLevelChangedEvent;
import com.parkio.analytics.application.port.AnalyticsEventRepository;
import com.parkio.analytics.application.port.DailyAnalyticsSnapshotRepository;
import com.parkio.analytics.application.port.InboxEventRepository;
import com.parkio.analytics.application.port.ParkingAnalyticsSnapshotRepository;
import com.parkio.analytics.application.port.UserAnalyticsSnapshotRepository;
import com.parkio.analytics.application.result.OverviewView;
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

/**
 * Behavioural unit tests for {@link AnalyticsApplicationService} using in-memory fake
 * ports — no Spring, no DB.
 */
class AnalyticsApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-07T12:00:00Z");

    private FakeAnalyticsEventRepository events;
    private FakeDailyRepository daily;
    private FakeUserRepository users;
    private FakeParkingRepository parking;
    private FakeInboxRepository inbox;
    private AnalyticsApplicationService service;

    @BeforeEach
    void setUp() {
        events = new FakeAnalyticsEventRepository();
        daily = new FakeDailyRepository();
        users = new FakeUserRepository();
        parking = new FakeParkingRepository();
        inbox = new FakeInboxRepository();
        service = new AnalyticsApplicationService(events, daily, users, parking, inbox,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void parkingCreatedIncrementsMetrics() {
        UUID owner = UUID.randomUUID();
        service.handleParkingSpotCreated(
                new ParkingSpotCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), owner, NOW));

        assertThat(service.getOverview().totalParkingCreated()).isEqualTo(1);
        assertThat(events.all).hasSize(1);
        assertThat(parking.byType).containsKey(AnalyticsMetricType.PARKING_CREATED);
        assertThat(users.byKey).containsKey(key(owner, AnalyticsMetricType.PARKING_CREATED));
    }

    @Test
    void parkingVerifiedIncrementsMetrics() {
        service.handleParkingSpotVerified(new ParkingSpotVerifiedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "AVAILABLE", NOW));

        assertThat(service.getOverview().totalParkingVerified()).isEqualTo(1);
    }

    @Test
    void pointsEarnedIncrementsMetricsBySumValue() {
        UUID user = UUID.randomUUID();
        service.handlePointsEarned(new PointsEarnedEvent(
                UUID.randomUUID(), user, 20, "PARKING_VERIFIED", 20, UUID.randomUUID(), NOW));

        assertThat(service.getOverview().totalPointsEarned()).isEqualTo(20);
        // Points accumulate as sum_value, counts as the number of events.
        assertThat(users.byKey.get(key(user, AnalyticsMetricType.POINTS_EARNED)).sumValue()).isEqualTo(20);
    }

    @Test
    void levelUpIncrementsMetrics() {
        service.handleUserLevelChanged(new UserLevelChangedEvent(
                UUID.randomUUID(), UUID.randomUUID(), 1, 2, 100, NOW));

        assertThat(service.getOverview().totalLevelUps()).isEqualTo(1);
    }

    @Test
    void notificationCreatedIncrementsMetrics() {
        service.handleNotificationCreated(new NotificationCreatedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "LEVEL_UP", "IN_APP", NOW));

        assertThat(service.getOverview().totalNotificationsCreated()).isEqualTo(1);
    }

    @Test
    void duplicateEventIsSkipped() {
        ParkingSpotCreatedEvent event =
                new ParkingSpotCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NOW);

        service.handleParkingSpotCreated(event);
        service.handleParkingSpotCreated(event); // redelivery

        assertThat(service.getOverview().totalParkingCreated()).isEqualTo(1);
        assertThat(events.all).hasSize(1);
    }

    @Test
    void overviewAggregatesAcrossEventTypes() {
        UUID owner = UUID.randomUUID();
        service.handleParkingSpotCreated(new ParkingSpotCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), owner, NOW));
        service.handleParkingSpotVerified(new ParkingSpotVerifiedEvent(UUID.randomUUID(), UUID.randomUUID(), owner, UUID.randomUUID(), "AVAILABLE", NOW));
        service.handleParkingSpotClaimed(new ParkingSpotClaimedEvent(UUID.randomUUID(), UUID.randomUUID(), owner, UUID.randomUUID(), NOW));
        service.handleParkingSpotRejected(new ParkingSpotRejectedEvent(UUID.randomUUID(), UUID.randomUUID(), owner, UUID.randomUUID(), "ILLEGAL_OR_RISKY", NOW));
        service.handlePointsEarned(new PointsEarnedEvent(UUID.randomUUID(), owner, 20, "PARKING_VERIFIED", 20, UUID.randomUUID(), NOW));
        service.handlePointsEarned(new PointsEarnedEvent(UUID.randomUUID(), owner, 30, "PARKING_CLAIMED", 50, UUID.randomUUID(), NOW));
        service.handleUserLevelChanged(new UserLevelChangedEvent(UUID.randomUUID(), owner, 1, 2, 50, NOW));
        service.handleNotificationCreated(new NotificationCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), owner, "LEVEL_UP", "IN_APP", NOW));

        OverviewView overview = service.getOverview();
        assertThat(overview.totalParkingCreated()).isEqualTo(1);
        assertThat(overview.totalParkingVerified()).isEqualTo(1);
        assertThat(overview.totalParkingClaimed()).isEqualTo(1);
        assertThat(overview.totalParkingRejected()).isEqualTo(1);
        assertThat(overview.totalPointsEarned()).isEqualTo(50); // 20 + 30
        assertThat(overview.totalLevelUps()).isEqualTo(1);
        assertThat(overview.totalNotificationsCreated()).isEqualTo(1);
    }

    private static String key(UUID userId, AnalyticsMetricType type) {
        return userId + "|" + type;
    }

    // --- Fakes -----------------------------------------------------------

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
        private final java.util.Set<UUID> processed = new java.util.HashSet<>();

        @Override
        public boolean existsByEventId(UUID eventId) {
            return processed.contains(eventId);
        }

        @Override
        public void markProcessed(UUID eventId, String eventType, Instant processedAt) {
            processed.add(eventId);
        }
    }
}
