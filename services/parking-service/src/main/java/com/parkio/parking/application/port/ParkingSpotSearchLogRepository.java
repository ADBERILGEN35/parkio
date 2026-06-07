package com.parkio.parking.application.port;

import com.parkio.parking.domain.ParkingSpotSearchLog;

/** Persistence port for {@link ParkingSpotSearchLog} (append-only). */
public interface ParkingSpotSearchLogRepository {

    ParkingSpotSearchLog save(ParkingSpotSearchLog searchLog);
}
