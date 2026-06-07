package com.parkio.parking.application.port;

import com.parkio.parking.domain.ParkingSpotStatusHistory;

/** Persistence port for {@link ParkingSpotStatusHistory} (append-only). */
public interface ParkingSpotStatusHistoryRepository {

    ParkingSpotStatusHistory save(ParkingSpotStatusHistory history);
}
