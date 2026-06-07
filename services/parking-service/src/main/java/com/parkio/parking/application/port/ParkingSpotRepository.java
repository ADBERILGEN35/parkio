package com.parkio.parking.application.port;

import com.parkio.parking.domain.ParkingSpot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link ParkingSpot}. */
public interface ParkingSpotRepository {

    ParkingSpot save(ParkingSpot spot);

    Optional<ParkingSpot> findById(UUID id);

    List<ParkingSpot> findByOwnerUserId(UUID ownerUserId);

    /**
     * Candidate spots within {@code radiusMeters} of the point, nearest first. The
     * production adapter pre-filters by status/expiry via PostGIS; the application
     * layer still enforces visibility as the authoritative rule.
     */
    List<ParkingSpot> findNearby(double latitude, double longitude, double radiusMeters, int limit);
}
