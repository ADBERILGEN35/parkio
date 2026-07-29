package com.parkio.parking.application;

import com.parkio.parking.application.port.OutboxEventAppender;
import com.parkio.parking.application.port.ParkingSessionRepository;
import com.parkio.parking.domain.ParkingSession;
import com.parkio.parking.domain.ParkingSessionCompletionReason;
import com.parkio.parking.domain.ParkingSessionReminderStage;
import com.parkio.parking.domain.ParkingSessionStatus;
import com.parkio.parking.domain.ParkingSessionStalePolicy;
import com.parkio.parking.domain.ParkingSource;
import com.parkio.parking.domain.event.ParkingHistoryDeletedEvent;
import com.parkio.parking.domain.event.ParkingSessionCancelledEvent;
import com.parkio.parking.domain.event.ParkingSessionCompletedEvent;
import com.parkio.parking.domain.event.ParkingSessionReminderRequestedEvent;
import com.parkio.parking.domain.event.ParkingSessionStartedEvent;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.infrastructure.config.ParkingProperties;
import com.parkio.parking.infrastructure.metrics.ParkingSessionLifecycleMetrics;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Application service for the parking-session lifecycle. */
@Service
@Transactional
public class ParkingSessionService {

    private static final Logger log = LoggerFactory.getLogger(ParkingSessionService.class);

    private final ParkingSessionRepository sessions;
    private final OutboxEventAppender outbox;
    private final Clock clock;
    private final ParkingSessionStalePolicy stalePolicy;
    private final ParkingProperties.Session sessionConfig;
    private final ParkingSessionLifecycleMetrics metrics;
    private final ParkingSessionStaleRowProcessor staleRows;

    public ParkingSessionService(
            ParkingSessionRepository sessions,
            OutboxEventAppender outbox,
            Clock clock,
            ParkingSessionStalePolicy stalePolicy,
            ParkingProperties parkingProperties,
            ParkingSessionLifecycleMetrics metrics,
            ParkingSessionStaleRowProcessor staleRows) {
        this.sessions = sessions;
        this.outbox = outbox;
        this.clock = clock;
        this.stalePolicy = Objects.requireNonNull(stalePolicy, "stalePolicy");
        this.sessionConfig = Objects.requireNonNull(parkingProperties, "parkingProperties").getSession();
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.staleRows = Objects.requireNonNull(staleRows, "staleRows");
        requirePositiveBatch(this.sessionConfig.getSchedulerBatchSize());
    }

    public ParkingSession startSession(UUID userId,
                                       ParkingSource parkingSource,
                                       double latitude,
                                       double longitude,
                                       BigDecimal estimatedFee,
                                       Instant reminderAt) {
        UUID ownerId = Objects.requireNonNull(userId, "userId");
        if (sessions.findActiveByUserId(ownerId).isPresent()) {
            throw new ParkingException(
                    ParkingErrorCode.ACTIVE_PARKING_SESSION_EXISTS,
                    "The user already has an active parking session.");
        }
        Instant now = clock.instant();
        ParkingSession session = ParkingSession.start(
                ownerId,
                parkingSource,
                latitude,
                longitude,
                estimatedFee,
                reminderAt,
                now);
        ParkingSession saved = sessions.save(session);
        outbox.append(ParkingSessionStartedEvent.of(saved, now));
        return saved;
    }

    public ParkingSession completeSession(UUID userId, UUID sessionId) {
        Instant now = clock.instant();
        ParkingSession session = requireOwnedSession(userId, sessionId);
        session.complete(now, ParkingSessionCompletionReason.MANUAL);
        ParkingSession saved = sessions.save(session);
        outbox.append(ParkingSessionCompletedEvent.of(saved, now));
        log.info(
                "USER_LEFT sessionId={} userId={} reason={} durationSeconds={}",
                saved.getId(),
                saved.getUserId(),
                ParkingSessionCompletionReason.MANUAL,
                durationSeconds(saved));
        return saved;
    }

    public ParkingSession cancelSession(UUID userId, UUID sessionId) {
        Instant now = clock.instant();
        ParkingSession session = requireOwnedSession(userId, sessionId);
        session.cancel(now);
        ParkingSession saved = sessions.save(session);
        outbox.append(ParkingSessionCancelledEvent.of(saved, now));
        return saved;
    }

    /**
     * Extends the confirmation window for an owned ACTIVE session ("Yes, still parked").
     */
    public ParkingSession confirmActiveSession(UUID userId, UUID sessionId) {
        Instant now = clock.instant();
        ParkingSession session = requireOwnedSession(userId, sessionId);
        session.confirmActive(now);
        ParkingSession saved = sessions.save(session);
        metrics.recordConfirmation();
        log.info(
                "CONFIRMED_ACTIVE sessionId={} userId={} confirmedAt={}",
                saved.getId(),
                saved.getUserId(),
                saved.getLastConfirmedAt());
        return saved;
    }

    /**
     * Completes one page of forgotten ACTIVE sessions using configured
     * {@link ParkingSessionStalePolicy#autoCompleteAfter()}. Each candidate runs in its own
     * transaction ({@link ParkingSessionStaleRowProcessor}) so concurrent scheduler nodes and
     * user mutations remain idempotent without poisoning sibling rows.
     *
     * @return number of sessions completed in this batch
     */
    public int autoCompleteStaleSessions(int batchSize) {
        return autoCompleteStaleSessionsPage(batchSize).succeeded();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ParkingSessionStaleBatchResult autoCompleteStaleSessionsPage(int batchSize) {
        if (!sessionConfig.isAutoCompleteEnabled()) {
            return ParkingSessionStaleBatchResult.empty();
        }
        requirePositiveBatch(batchSize);
        Instant now = clock.instant();
        Instant threshold = now.minus(stalePolicy.autoCompleteAfter());
        List<ParkingSession> candidates = sessions.findStaleActiveCandidates(
                threshold, threshold, batchSize);
        if (candidates.isEmpty()) {
            return ParkingSessionStaleBatchResult.empty();
        }
        int completed = 0;
        for (ParkingSession candidate : candidates) {
            if (staleRows.tryAutoComplete(candidate.getId(), now)) {
                completed++;
            }
        }
        metrics.recordAutoCompleted(completed);
        metrics.recordSchedulerProcessed(candidates.size());
        return new ParkingSessionStaleBatchResult(candidates.size(), completed);
    }

    /**
     * Publishes one page of due reminder events for the given next stage and persists
     * {@code reminderStage} so the same reminder is never sent twice. Each candidate is
     * processed in its own transaction.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ParkingSessionStaleBatchResult sendDueRemindersPage(
            ParkingSessionReminderStage stage, int batchSize) {
        if (!sessionConfig.isRemindersEnabled() || !sessionConfig.isNotificationEnabled()) {
            return ParkingSessionStaleBatchResult.empty();
        }
        Objects.requireNonNull(stage, "stage");
        if (stage == ParkingSessionReminderStage.NONE) {
            throw new IllegalArgumentException("stage must be FIRST or SECOND");
        }
        requirePositiveBatch(batchSize);
        Instant now = clock.instant();
        Instant confirmedThreshold = now.minus(
                stage == ParkingSessionReminderStage.FIRST
                        ? stalePolicy.confirmAfter()
                        : stalePolicy.reminder2After());
        Instant startedThreshold = stage == ParkingSessionReminderStage.SECOND
                ? now.minus(stalePolicy.reminder2After())
                : null;
        int currentStage = stage == ParkingSessionReminderStage.FIRST
                ? ParkingSessionReminderStage.NONE.wireValue()
                : ParkingSessionReminderStage.FIRST.wireValue();
        List<ParkingSession> candidates = sessions.findReminderCandidates(
                currentStage, confirmedThreshold, startedThreshold, batchSize);
        if (candidates.isEmpty()) {
            return ParkingSessionStaleBatchResult.empty();
        }
        int sent = 0;
        for (ParkingSession candidate : candidates) {
            if (staleRows.trySendReminder(candidate.getId(), stage, now)) {
                sent++;
            }
        }
        metrics.recordReminderSent(stage.name(), sent);
        metrics.recordSchedulerProcessed(candidates.size());
        return new ParkingSessionStaleBatchResult(candidates.size(), sent);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ParkingSessionStaleBatchResult purgeExpiredHistoryPage(int batchSize) {
        if (!sessionConfig.isRetentionEnabled()) {
            return ParkingSessionStaleBatchResult.empty();
        }
        requirePositiveBatch(batchSize);
        Duration retentionAfter = sessionConfig.getRetentionAfter();
        if (retentionAfter == null || retentionAfter.isNegative() || retentionAfter.isZero()) {
            throw new IllegalStateException("retentionAfter must be positive when retention is enabled");
        }
        Instant cutoff = clock.instant().minus(retentionAfter);
        int deleted = sessions.deleteTerminalEndedAtOrBefore(cutoff, batchSize);
        if (deleted > 0) {
            metrics.recordRetentionDeleted(deleted);
            metrics.recordSchedulerProcessed(deleted);
            log.info("HISTORY_PURGED deleted={} endedAtOrBefore={}", deleted, cutoff);
        }
        return new ParkingSessionStaleBatchResult(deleted, deleted);
    }

    public void refreshActiveSessionGauge() {
        metrics.refreshActiveCount(() -> sessions.countByStatus(ParkingSessionStatus.ACTIVE));
    }

    @Transactional(readOnly = true)
    public Optional<ParkingSession> findActive(UUID userId) {
        return sessions.findActiveByUserId(Objects.requireNonNull(userId, "userId"));
    }

    @Transactional(readOnly = true)
    public ParkingSessionHistoryPage findHistory(UUID userId, int pageSize) {
        return sessions.findHistoryByUserId(
                Objects.requireNonNull(userId, "userId"),
                ParkingSessionRepository.requireValidHistoryPageSize(pageSize));
    }

    @Transactional(readOnly = true)
    public ParkingSessionHistoryPage findHistory(
            UUID userId, ParkingSessionHistoryCursor cursor, int pageSize) {
        return sessions.findHistoryByUserId(
                Objects.requireNonNull(userId, "userId"),
                Objects.requireNonNull(cursor, "cursor"),
                ParkingSessionRepository.requireValidHistoryPageSize(pageSize));
    }

    /**
     * Hard-deletes one owned terminal session.
     * Missing, foreign, and already-deleted ids are treated as successful no-ops.
     * ACTIVE owned sessions raise {@link ParkingErrorCode#PARKING_SESSION_NOT_TERMINAL}.
     */
    public void deleteTerminalSession(UUID userId, UUID sessionId) {
        UUID ownerId = Objects.requireNonNull(userId, "userId");
        UUID id = Objects.requireNonNull(sessionId, "sessionId");

        int deleted = sessions.deleteTerminalByIdAndUserId(id, ownerId);
        if (deleted > 0) {
            outbox.append(ParkingHistoryDeletedEvent.ofSingle(ownerId, id, clock.instant()));
            return;
        }

        Optional<ParkingSessionStatus> status = sessions.findStatusByIdAndUserId(id, ownerId);
        if (status.isEmpty()) {
            return;
        }
        if (status.get() == ParkingSessionStatus.ACTIVE) {
            throw new ParkingException(
                    ParkingErrorCode.PARKING_SESSION_NOT_TERMINAL,
                    "An active parking session cannot be deleted.");
        }

        sessions.deleteTerminalByIdAndUserId(id, ownerId);
    }

    /**
     * Hard-deletes all owned COMPLETED/CANCELLED sessions. ACTIVE rows are preserved.
     */
    public void deleteTerminalHistory(UUID userId) {
        UUID ownerId = Objects.requireNonNull(userId, "userId");
        int deleted = sessions.deleteAllTerminalByUserId(ownerId);
        if (deleted > 0) {
            outbox.append(ParkingHistoryDeletedEvent.ofAllTerminalHistory(
                    ownerId, deleted, clock.instant()));
        }
    }

    private ParkingSession requireOwnedSession(UUID userId, UUID sessionId) {
        UUID ownerId = Objects.requireNonNull(userId, "userId");
        UUID id = Objects.requireNonNull(sessionId, "sessionId");
        return sessions.findByIdAndUserId(id, ownerId)
                .orElseThrow(() -> new ParkingException(
                        ParkingErrorCode.PARKING_SESSION_NOT_FOUND,
                        "Parking session was not found."));
    }

    private static void requirePositiveBatch(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
    }

    private static Long durationSeconds(ParkingSession session) {
        Instant started = session.getStartedAt();
        Instant ended = session.getEndedAt();
        if (started == null || ended == null || ended.isBefore(started)) {
            return null;
        }
        return Duration.between(started, ended).toSeconds();
    }
}
