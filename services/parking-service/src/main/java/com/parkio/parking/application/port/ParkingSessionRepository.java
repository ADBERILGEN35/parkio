package com.parkio.parking.application.port;

import com.parkio.parking.application.ParkingSessionHistoryCursor;
import com.parkio.parking.application.ParkingSessionHistoryPage;
import com.parkio.parking.domain.ParkingSession;
import com.parkio.parking.domain.ParkingSessionStatus;
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
}
