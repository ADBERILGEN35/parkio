package com.parkio.parking.application.port;

import com.parkio.parking.domain.ParkingSpotStatusHistory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutcomeStatusHistoryReadPort {

    List<ParkingSpotStatusHistory> findStatusHistoryForOutcome(UUID parkingSpotId, Instant cutoffInclusive);
}