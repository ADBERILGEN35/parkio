package com.parkio.parking.application.command;

import java.util.UUID;

/**
 * Nearby-search request. {@code radiusMeters} and {@code limit} are optional;
 * {@code null} means "use the configured default".
 */
public record SearchNearbyQuery(
        UUID searcherUserId,
        double latitude,
        double longitude,
        Double radiusMeters,
        Integer limit) {
}
