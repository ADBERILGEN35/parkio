package com.parkio.analytics.application;

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
import com.parkio.analytics.domain.AnalyticsMetric;
import com.parkio.analytics.domain.AnalyticsMetricType;
import com.parkio.analytics.domain.DailyAnalyticsSnapshot;
import com.parkio.analytics.domain.ParkingAnalyticsSnapshot;
import com.parkio.analytics.domain.UserAnalyticsSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Analytics use cases: idempotently ingesting upstream events into a raw audit log
 * plus rolling snapshots (daily / per-user / parking-funnel), and serving KPI reads.
 * Depends only on domain types and ports (ai-context/01).
 *
 * <p>Analytics is <strong>projection-only</strong> (ai-context/03): it never modifies
 * source business data and owns no business decisions. Raw events are retained so
 * snapshots can be recomputed.
 */
@Service
@Transactional
public class AnalyticsApplicationService {

    private final AnalyticsEventRepository analyticsEvents;
    private final DailyAnalyticsSnapshotRepository dailySnapshots;
    private final UserAnalyticsSnapshotRepository userSnapshots;
    private final ParkingAnalyticsSnapshotRepository parkingSnapshots;
    private final InboxEventRepository inbox;
    private final Clock clock;

    public AnalyticsApplicationService(AnalyticsEventRepository analyticsEvents,
                                       DailyAnalyticsSnapshotRepository dailySnapshots,
                                       UserAnalyticsSnapshotRepository userSnapshots,
                                       ParkingAnalyticsSnapshotRepository parkingSnapshots,
                                       InboxEventRepository inbox,
                                       Clock clock) {
        this.analyticsEvents = analyticsEvents;
        this.dailySnapshots = dailySnapshots;
        this.userSnapshots = userSnapshots;
        this.parkingSnapshots = parkingSnapshots;
        this.inbox = inbox;
        this.clock = clock;
    }

    // --- Event handlers (invoked directly for now; a Kafka consumer will call them) ---

    public void handleParkingSpotCreated(ParkingSpotCreatedEvent event) {
        ingest(event.eventId(), AnalyticsMetricType.PARKING_CREATED, event.ownerUserId(),
                event.parkingSpotId(), 0, event.occurredAt(), "ParkingSpotCreated");
    }

    public void handleParkingSpotVerified(ParkingSpotVerifiedEvent event) {
        ingest(event.eventId(), AnalyticsMetricType.PARKING_VERIFIED, event.actorUserId(),
                event.parkingSpotId(), 0, event.occurredAt(), "ParkingSpotVerified");
    }

    public void handleParkingSpotClaimed(ParkingSpotClaimedEvent event) {
        ingest(event.eventId(), AnalyticsMetricType.PARKING_CLAIMED, event.actorUserId(),
                event.parkingSpotId(), 0, event.occurredAt(), "ParkingSpotClaimed");
    }

    public void handleParkingSpotRejected(ParkingSpotRejectedEvent event) {
        ingest(event.eventId(), AnalyticsMetricType.PARKING_REJECTED, event.ownerUserId(),
                event.parkingSpotId(), 0, event.occurredAt(), "ParkingSpotRejected");
    }

    public void handlePointsEarned(PointsEarnedEvent event) {
        ingest(event.eventId(), AnalyticsMetricType.POINTS_EARNED, event.userId(),
                null, event.points(), event.occurredAt(), "PointsEarned");
    }

    public void handleUserLevelChanged(UserLevelChangedEvent event) {
        ingest(event.eventId(), AnalyticsMetricType.LEVEL_UP, event.userId(),
                null, 0, event.occurredAt(), "UserLevelChanged");
    }

    public void handleNotificationCreated(NotificationCreatedEvent event) {
        ingest(event.eventId(), AnalyticsMetricType.NOTIFICATION_CREATED, event.userId(),
                event.notificationId(), 0, event.occurredAt(), "NotificationCreated");
    }

    // --- Queries ---

    @Transactional(readOnly = true)
    public OverviewView getOverview() {
        Map<AnalyticsMetricType, AnalyticsMetric> metrics = aggregateAllMetrics();
        return new OverviewView(
                metrics.get(AnalyticsMetricType.PARKING_CREATED).totalCount(),
                metrics.get(AnalyticsMetricType.PARKING_VERIFIED).totalCount(),
                metrics.get(AnalyticsMetricType.PARKING_CLAIMED).totalCount(),
                metrics.get(AnalyticsMetricType.PARKING_REJECTED).totalCount(),
                metrics.get(AnalyticsMetricType.POINTS_EARNED).totalValue(),
                metrics.get(AnalyticsMetricType.LEVEL_UP).totalCount(),
                metrics.get(AnalyticsMetricType.NOTIFICATION_CREATED).totalCount());
    }

    @Transactional(readOnly = true)
    public List<AnalyticsMetric> getMetrics() {
        Map<AnalyticsMetricType, AnalyticsMetric> metrics = aggregateAllMetrics();
        return List.of(AnalyticsMetricType.values()).stream().map(metrics::get).toList();
    }

    @Transactional(readOnly = true)
    public List<DailyAnalyticsSnapshot> getDailySnapshots() {
        return dailySnapshots.findAll();
    }

    @Transactional(readOnly = true)
    public List<UserAnalyticsSnapshot> getUserAnalytics(UUID userId) {
        return userSnapshots.findByUserId(userId);
    }

    @Transactional(readOnly = true)
    public List<ParkingAnalyticsSnapshot> getParkingAnalytics() {
        return parkingSnapshots.findAll();
    }

    // --- Internals ---

    /**
     * Records the raw event and updates the daily / user / parking snapshots, then
     * marks the event processed — all in one transaction. Idempotent: a previously
     * processed {@code sourceEventId} is skipped.
     */
    private void ingest(UUID sourceEventId, AnalyticsMetricType metricType, UUID userId,
                        UUID relatedEntityId, long value, Instant occurredAt, String eventType) {
        if (inbox.existsByEventId(sourceEventId)) {
            return;
        }
        Instant now = clock.instant();

        analyticsEvents.save(AnalyticsEvent.record(sourceEventId, metricType, userId, relatedEntityId,
                value, occurredAt, now));

        LocalDate day = occurredAt.atZone(ZoneOffset.UTC).toLocalDate();
        DailyAnalyticsSnapshot daily = dailySnapshots.findByDateAndMetricType(day, metricType)
                .orElseGet(() -> DailyAnalyticsSnapshot.start(day, metricType, now));
        daily.increment(1, value, now);
        dailySnapshots.save(daily);

        if (userId != null) {
            UserAnalyticsSnapshot user = userSnapshots.findByUserIdAndMetricType(userId, metricType)
                    .orElseGet(() -> UserAnalyticsSnapshot.start(userId, metricType, now));
            user.increment(1, value, now);
            userSnapshots.save(user);
        }

        if (metricType.isParking()) {
            ParkingAnalyticsSnapshot parking = parkingSnapshots.findByMetricType(metricType)
                    .orElseGet(() -> ParkingAnalyticsSnapshot.start(metricType, now));
            parking.increment(1, value, now);
            parkingSnapshots.save(parking);
        }

        inbox.markProcessed(sourceEventId, eventType, now);
    }

    /** Lifetime totals per metric type (zero-filled), aggregated from daily snapshots. */
    private Map<AnalyticsMetricType, AnalyticsMetric> aggregateAllMetrics() {
        Map<AnalyticsMetricType, long[]> accumulator = new EnumMap<>(AnalyticsMetricType.class);
        for (AnalyticsMetricType type : AnalyticsMetricType.values()) {
            accumulator.put(type, new long[2]);
        }
        for (DailyAnalyticsSnapshot snapshot : dailySnapshots.findAll()) {
            long[] totals = accumulator.get(snapshot.metricType());
            totals[0] += snapshot.eventCount();
            totals[1] += snapshot.sumValue();
        }
        Map<AnalyticsMetricType, AnalyticsMetric> metrics = new EnumMap<>(AnalyticsMetricType.class);
        accumulator.forEach((type, totals) ->
                metrics.put(type, new AnalyticsMetric(type, totals[0], totals[1])));
        return metrics;
    }
}
