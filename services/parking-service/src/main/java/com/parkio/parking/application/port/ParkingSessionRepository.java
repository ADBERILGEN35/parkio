package com.parkio.parking.application.port;

import com.parkio.parking.application.ParkingSessionHistoryCursor;
import com.parkio.parking.application.ParkingSessionHistoryPage;
import com.parkio.parking.domain.ParkingSession;
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
}
