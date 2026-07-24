package com.parkio.parking.application;

import com.parkio.parking.domain.ParkingSession;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable bounded result for one page of parking-session history. */
public record ParkingSessionHistoryPage(List<ParkingSession> sessions, boolean hasNext) {

    public ParkingSessionHistoryPage {
        sessions = List.copyOf(Objects.requireNonNull(sessions, "sessions"));
        if (hasNext && sessions.isEmpty()) {
            throw new IllegalArgumentException("A page with a continuation cannot be empty");
        }
    }

    public Optional<ParkingSessionHistoryCursor> nextCursor() {
        if (!hasNext) {
            return Optional.empty();
        }
        ParkingSession lastSession = sessions.getLast();
        return Optional.of(new ParkingSessionHistoryCursor(
                lastSession.getStartedAt(), lastSession.getId()));
    }
}
