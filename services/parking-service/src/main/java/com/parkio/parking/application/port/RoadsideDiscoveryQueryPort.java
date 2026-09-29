package com.parkio.parking.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only published İZELMAN roadside segments for discovery surfaces. */
public interface RoadsideDiscoveryQueryPort {
    record RoadsideSegment(
            UUID id,
            String displayName,
            String addressText,
            double latitude,
            double longitude,
            Integer capacityTotal,
            Instant updatedAt) {}

    long countNearby(double lat, double lng, int radiusMeters);

    List<RoadsideSegment> nearby(double lat, double lng, int radiusMeters, int limit);
}
