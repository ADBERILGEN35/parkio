package com.parkio.parking.application.port;

import com.parkio.parking.domain.ParkingSpotViewLog;

/** Persistence port for {@link ParkingSpotViewLog} (append-only). */
public interface ParkingSpotViewLogRepository {

    ParkingSpotViewLog save(ParkingSpotViewLog viewLog);
}
