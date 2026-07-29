package com.parkio.parking.decision.port;

import com.parkio.parking.decision.score.AvailabilityScore;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Freshness / availability assessment input (ADR-WP05 {@code AvailabilityPort}).
 *
 * <p>No TTL mutation. No implementation in WP-05.2.
 */
public interface AvailabilityPort {

    Optional<AvailabilityScore> assessAvailability(UUID parkingSpotId, Instant at);
}