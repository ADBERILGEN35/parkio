package com.parkio.parking.application.port;

import com.parkio.parking.application.ParkingSessionHistoryCursor;
import com.parkio.parking.application.ParkingSessionHistoryPage;
import com.parkio.parking.domain.ParkingSession;
import com.parkio.parking.domain.ParkingSessionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence operations required by parking-session use cases. */
public interface ParkingSessionRepository {

    int MAX_HISTORY_PAGE_SIZE = 100;

    static int requireValidHistoryPageSize(int pageSize) {
        if (pageSize < 1 || pageSize > MAX_HISTORY_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "history pageSize must be between 1 and " + MAX_HISTORY_PAGE_SIZE);
        }
        return pageSize;
    }

    ParkingSession save(ParkingSession session);

    Optional<ParkingSession> findById(UUID id);

    Optional<ParkingSession> findActiveByUserId(UUID userId);

    Optional<ParkingSession> findByIdAndUserId(UUID id, UUID userId);

    ParkingSessionHistoryPage findHistoryByUserId(UUID userId, int pageSize);

    ParkingSessionHistoryPage findHistoryByUserId(
            UUID userId, ParkingSessionHistoryCursor cursor, int pageSize);

    /**
     * Hard-deletes one COMPLETED/CANCELLED session owned by {@code userId}.
     *
     * @return number of rows deleted (0 or 1)
     */
    int deleteTerminalByIdAndUserId(UUID id, UUID userId);

    /**
     * Hard-deletes all COMPLETED/CANCELLED sessions owned by {@code userId}.
     * ACTIVE rows are never matched.
     *
     * @return number of rows deleted
     */
    int deleteAllTerminalByUserId(UUID userId);

    /** Ownership-scoped status probe without loading coordinate fields into the domain aggregate. */
    Optional<ParkingSessionStatus> findStatusByIdAndUserId(UUID id, UUID userId);

    /**
     * ACTIVE sessions whose confirmation heartbeat and start are both at or before the
     * given thresholds, oldest confirmation first. Used by the stale auto-complete job.
     */
    List<ParkingSession> findStaleActiveCandidates(
            Instant confirmedAtOrBefore, Instant startedAtOrBefore, int limit);

    /**
     * ACTIVE sessions eligible for a specific reminder stage (exact current stage match),
     * oldest confirmation first.
     *
     * @param currentReminderStage wire value of the stage already reached (0 before FIRST,
     *                             1 before SECOND)
     * @param confirmedAtOrBefore confirmation anchor must be at or before this instant
     * @param startedAtOrBefore optional start ceiling; null skips the startedAt predicate
     */
    List<ParkingSession> findReminderCandidates(
            int currentReminderStage,
            Instant confirmedAtOrBefore,
            Instant startedAtOrBefore,
            int limit);

    /** Approximate ACTIVE session count for gauges. */
    long countByStatus(ParkingSessionStatus status);

    /**
     * Deletes up to {@code limit} terminal sessions whose {@code endedAt} is at or before
     * {@code endedAtOrBefore}. Used only when retention is explicitly enabled.
     *
     * @return number of rows deleted
     */
    int deleteTerminalEndedAtOrBefore(Instant endedAtOrBefore, int limit);
}
