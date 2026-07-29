package com.parkio.parking.availability.port;

import com.parkio.parking.availability.AvailabilitySnapshot;
import java.util.Optional;
import java.util.UUID;

/**
 * Future persistence boundary for availability evaluation history.
 *
 * <p>WP-05.9 keeps the engine pure; no storage implementation is provided.
 * Do not overload {@code decision.audit.DecisionAuditPort}.
 */
public interface AvailabilityHistoryPort {

    void append(AvailabilitySnapshot snapshot);

    Optional<AvailabilitySnapshot> findLatest(UUID parkingSpotId);

    /** No-op implementation until WP-05.10+ persistence work. */
    static AvailabilityHistoryPort noop() {
        return new AvailabilityHistoryPort() {
            @Override
            public void append(AvailabilitySnapshot snapshot) {}

            @Override
            public Optional<AvailabilitySnapshot> findLatest(UUID parkingSpotId) {
                return Optional.empty();
            }
        };
    }
}
