package com.parkio.parking.application;

import com.parkio.parking.application.port.OutboxEventAppender;
import com.parkio.parking.application.port.ParkingSessionRepository;
import com.parkio.parking.domain.ParkingSession;
import com.parkio.parking.domain.ParkingSessionCompletionReason;
import com.parkio.parking.domain.ParkingSessionReminderStage;
import com.parkio.parking.domain.ParkingSessionStalePolicy;
import com.parkio.parking.domain.event.ParkingSessionCompletedEvent;
import com.parkio.parking.domain.event.ParkingSessionReminderRequestedEvent;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-candidate stale-session mutations in independent transactions so optimistic-lock
 * conflicts cannot poison an entire page batch or roll back sibling outbox rows.
 */
@Component
public class ParkingSessionStaleRowProcessor {

    private static final Logger log = LoggerFactory.getLogger(ParkingSessionStaleRowProcessor.class);

    private final ParkingSessionRepository sessions;
    private final OutboxEventAppender outbox;
    private final ParkingSessionStalePolicy stalePolicy;

    public ParkingSessionStaleRowProcessor(
            ParkingSessionRepository sessions,
            OutboxEventAppender outbox,
            ParkingSessionStalePolicy stalePolicy) {
        this.sessions = sessions;
        this.outbox = outbox;
        this.stalePolicy = Objects.requireNonNull(stalePolicy, "stalePolicy");
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = OptimisticLockingFailureException.class)
    public boolean tryAutoComplete(UUID sessionId, Instant now) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(now, "now");
        Optional<ParkingSession> loaded = sessions.findById(sessionId);
        if (loaded.isEmpty()) {
            return false;
        }
        ParkingSession candidate = loaded.get();
        if (!stalePolicy.isEligibleForAutoComplete(candidate, now)) {
            return false;
        }
        try {
            candidate.complete(now, ParkingSessionCompletionReason.AUTO_TIMEOUT);
            ParkingSession saved = sessions.save(candidate);
            outbox.append(ParkingSessionCompletedEvent.of(saved, now));
            log.info(
                    "AUTO_COMPLETED sessionId={} userId={} reason={} durationSeconds={} startedAt={} lastConfirmedAt={}",
                    saved.getId(),
                    saved.getUserId(),
                    ParkingSessionCompletionReason.AUTO_TIMEOUT,
                    durationSeconds(saved),
                    saved.getStartedAt(),
                    saved.getLastConfirmedAt());
            return true;
        } catch (ParkingException exception) {
            if (exception.errorCode() == ParkingErrorCode.PARKING_SESSION_NOT_ACTIVE) {
                return false;
            }
            throw exception;
        } catch (OptimisticLockingFailureException exception) {
            log.debug("Skipping auto-complete due to concurrent update sessionId={}", sessionId);
            return false;
        }
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = OptimisticLockingFailureException.class)
    public boolean trySendReminder(UUID sessionId, ParkingSessionReminderStage stage, Instant now) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(now, "now");
        if (stage == ParkingSessionReminderStage.NONE) {
            throw new IllegalArgumentException("stage must be FIRST or SECOND");
        }
        Optional<ParkingSession> loaded = sessions.findById(sessionId);
        if (loaded.isEmpty()) {
            return false;
        }
        ParkingSession candidate = loaded.get();
        Optional<ParkingSessionReminderStage> due = stalePolicy.nextReminderStage(candidate, now);
        if (due.isEmpty() || due.get() != stage) {
            return false;
        }
        try {
            candidate.markReminderSent(stage, now);
            ParkingSession saved = sessions.save(candidate);
            outbox.append(ParkingSessionReminderRequestedEvent.of(saved, stage, now));
            log.info(
                    "REMINDER_SENT sessionId={} userId={} stage={} reason=STALE_CONFIRMATION",
                    saved.getId(),
                    saved.getUserId(),
                    stage);
            return true;
        } catch (ParkingException exception) {
            if (exception.errorCode() == ParkingErrorCode.PARKING_SESSION_NOT_ACTIVE) {
                return false;
            }
            throw exception;
        } catch (OptimisticLockingFailureException exception) {
            log.debug("Skipping reminder due to concurrent update sessionId={}", sessionId);
            return false;
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