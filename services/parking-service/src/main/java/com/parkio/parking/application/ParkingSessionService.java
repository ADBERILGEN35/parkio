package com.parkio.parking.application;

import com.parkio.parking.application.port.ParkingSessionRepository;
import com.parkio.parking.domain.ParkingSession;
import com.parkio.parking.domain.ParkingSource;
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
    private final Clock clock;

    public ParkingSessionService(ParkingSessionRepository sessions, Clock clock) {
        this.sessions = sessions;
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
        ParkingSession session = ParkingSession.start(
                ownerId,
                parkingSource,
                latitude,
                longitude,
                estimatedFee,
                reminderAt,
                clock.instant());
        return sessions.save(session);
    }

    public ParkingSession completeSession(UUID userId, UUID sessionId) {
        ParkingSession session = requireOwnedSession(userId, sessionId);
        session.complete(clock.instant());
        return sessions.save(session);
    }

    public ParkingSession cancelSession(UUID userId, UUID sessionId) {
        ParkingSession session = requireOwnedSession(userId, sessionId);
        session.cancel(clock.instant());
        return sessions.save(session);
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

    private ParkingSession requireOwnedSession(UUID userId, UUID sessionId) {
        UUID ownerId = Objects.requireNonNull(userId, "userId");
        UUID id = Objects.requireNonNull(sessionId, "sessionId");
        return sessions.findByIdAndUserId(id, ownerId)
                .orElseThrow(() -> new ParkingException(
                        ParkingErrorCode.PARKING_SESSION_NOT_FOUND,
                        "Parking session was not found."));
    }
}
