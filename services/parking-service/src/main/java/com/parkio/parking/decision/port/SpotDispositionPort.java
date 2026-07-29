package com.parkio.parking.decision.port;

import com.parkio.parking.decision.DecisionResult;
import java.util.UUID;

/**
 * Applies a Decision result to the ParkingSpot aggregate
 * (ADR-WP05 {@code SpotDispositionPort}).
 *
 * <p>Owned by parking domain adapters. No implementation and no runtime wiring in WP-05.2.
 */
public interface SpotDispositionPort {

    void apply(UUID parkingSpotId, DecisionResult decision);
}