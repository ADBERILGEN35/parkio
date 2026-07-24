package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.application.ParkingSessionHistoryCursor;
import com.parkio.parking.application.ParkingSessionHistoryPage;
import com.parkio.parking.application.port.ParkingSessionRepository;
import com.parkio.parking.domain.ParkingSession;
import com.parkio.parking.domain.ParkingSessionStatus;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.infrastructure.persistence.jpa.ParkingSessionJpaRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Component;

/** Adapts the parking-session repository port to Spring Data JPA. */
@Component
public class ParkingSessionRepositoryAdapter implements ParkingSessionRepository {

    static final String ACTIVE_SESSION_UNIQUE_INDEX = "uq_parking_sessions_active_user";

    private final ParkingSessionJpaRepository jpa;

    public ParkingSessionRepositoryAdapter(ParkingSessionJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ParkingSession save(ParkingSession session) {
        try {
            return jpa.saveAndFlush(session);
        } catch (DataIntegrityViolationException exception) {
            if (violatesConstraint(exception, ACTIVE_SESSION_UNIQUE_INDEX)) {
                throw new ParkingException(
                        ParkingErrorCode.ACTIVE_PARKING_SESSION_EXISTS,
                        "The user already has an active parking session.");
            }
            throw exception;
        }
    }

    @Override
    public Optional<ParkingSession> findActiveByUserId(UUID userId) {
        return jpa.findByUserIdAndStatus(userId, ParkingSessionStatus.ACTIVE);
    }

    @Override
    public Optional<ParkingSession> findByIdAndUserId(UUID id, UUID userId) {
        return jpa.findByIdAndUserId(id, userId);
    }

    @Override
    public ParkingSessionHistoryPage findHistoryByUserId(UUID userId, int pageSize) {
        int boundedPageSize = ParkingSessionRepository.requireValidHistoryPageSize(pageSize);
        return toHistoryPage(jpa.findHistoryByUserId(userId, PageRequest.of(0, boundedPageSize)));
    }

    @Override
    public ParkingSessionHistoryPage findHistoryByUserId(
            UUID userId, ParkingSessionHistoryCursor cursor, int pageSize) {
        int boundedPageSize = ParkingSessionRepository.requireValidHistoryPageSize(pageSize);
        ParkingSessionHistoryCursor requiredCursor = Objects.requireNonNull(cursor, "cursor");
        return toHistoryPage(jpa.findHistoryByUserIdAfter(
                userId,
                requiredCursor.startedAt(),
                requiredCursor.id(),
                PageRequest.of(0, boundedPageSize)));
    }

    private static ParkingSessionHistoryPage toHistoryPage(Slice<ParkingSession> slice) {
        return new ParkingSessionHistoryPage(slice.getContent(), slice.hasNext());
    }

    private static boolean violatesConstraint(Throwable failure, String constraintName) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation
                    && constraintName.equals(constraintViolation.getConstraintName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
