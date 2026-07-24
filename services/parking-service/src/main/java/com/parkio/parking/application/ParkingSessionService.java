package com.parkio.parking.application;

import com.parkio.parking.application.port.OutboxEventAppender;
import com.parkio.parking.application.port.ParkingSessionRepository;
import com.parkio.parking.domain.ParkingSession;
import com.parkio.parking.domain.ParkingSessionStatus;
import com.parkio.parking.domain.ParkingSource;
import com.parkio.parking.domain.event.ParkingSessionCancelledEvent;
import com.parkio.parking.domain.event.ParkingSessionCompletedEvent;
import com.parkio.parking.domain.event.ParkingSessionStartedEvent;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Application service for the parking-session lifecycle. */
@Service
@Transactional
public class ParkingSessionService {

    private final ParkingSessionRepository sessions;
    private final OutboxEventAppender outbox;
    private final Clock clock;

    public ParkingSessionService(
            ParkingSessionRepository sessions, OutboxEventAppender outbox, Clock clock) {
        this.sessions = sessions;
        this.outbox = outbox;
        this.clock = clock;
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
        session.complete(now);
        ParkingSession saved = sessions.save(session);
        outbox.append(ParkingSessionCompletedEvent.of(saved, now));
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
            return;
        }

        Optional<ParkingSessionStatus> status = sessions.findStatusByIdAndUserId(id, ownerId);
        if (status.isEmpty()) {
            // Missing, foreign-owned, or already deleted — opaque success.
            return;
        }
        if (status.get() == ParkingSessionStatus.ACTIVE) {
            throw new ParkingException(
                    ParkingErrorCode.PARKING_SESSION_NOT_TERMINAL,
                    "An active parking session cannot be deleted.");
        }

        // Race: row became terminal after the first delete miss — delete once more.
        sessions.deleteTerminalByIdAndUserId(id, ownerId);
    }

    /**
     * Hard-deletes all owned COMPLETED/CANCELLED sessions. ACTIVE rows are preserved.
     */
    public void deleteTerminalHistory(UUID userId) {
        sessions.deleteAllTerminalByUserId(Objects.requireNonNull(userId, "userId"));
    }

    private ParkingSession requireOwnedSession(UUID userId, UUID sessionId) {
        UUID ownerId = Objects.requireNonNull(userId, "userId");
        UUID id = Objects.requireNonNull(sessionId, "sessionId");
        return sessions.findByIdAndUserId(id, ownerId)
                .orElseThrow(() -> new ParkingException(
                        ParkingErrorCode.PARKING_SESSION_NOT_FOUND,
                        "Parking session was not found."));
    }
}
