package com.parkio.parking.availability.expiration;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Temporal expiration view for an availability evaluation at a fixed instant.
 */
public record AvailabilityExpiration(
        Instant expiresAt,
        Instant evaluatedAt,
        boolean expired,
        Duration remaining) {

    public AvailabilityExpiration {
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        if (expiresAt != null && remaining == null) {
            throw new IllegalArgumentException("remaining is required when expiresAt is present");
        }
        if (expiresAt == null && remaining != null) {
            throw new IllegalArgumentException("remaining must be null when expiresAt is absent");
        }
    }

    public static AvailabilityExpiration of(Instant expiresAt, Instant evaluatedAt) {
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        if (expiresAt == null) {
            return new AvailabilityExpiration(null, evaluatedAt, false, null);
        }
        boolean expired = !evaluatedAt.isBefore(expiresAt);
        Duration remaining = expired ? Duration.ZERO : Duration.between(evaluatedAt, expiresAt);
        return new AvailabilityExpiration(expiresAt, evaluatedAt, expired, remaining);
    }
}
