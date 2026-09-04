package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.externalsource.discovery.DiscoveryDuplicatePresentationPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Bounded internal metrics for DATA-WP-07 nearby duplicate presentation.
 * Labels never include facility ID, name, address, coordinates, or request ID.
 */
@Component
public class DiscoveryDuplicatePresentationMetrics {
    private final MeterRegistry registry;

    public DiscoveryDuplicatePresentationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(DiscoveryDuplicatePresentationPolicy.ApplyResult<?> result) {
        if (result == null) {
            return;
        }
        increment("considered", result.considered(), "none", "considered");
        increment("strong_duplicates", result.strongDuplicates(), "IZUM_OSM", "presentation_duplicate");
        increment("suppressed", result.suppressed(), "IZUM_OSM", "suppressed");
        increment("hard_conflict_vetoes", result.hardConflictVetoes(), "IZUM_OSM", "hard_conflict");
        increment("distance_only_rejected", result.distanceOnlyRejected(), "IZUM_OSM", "distance_only");
        increment("name_only_rejected", result.nameOnlyRejected(), "IZUM_OSM", "name_only");
        if (result.overfetchExhausted()) {
            increment("overfetch_exhausted", 1, "IZUM_OSM", "overfetch_exhausted");
        }
    }

    private void increment(String outcome, int amount, String pair, String reason) {
        if (amount <= 0) {
            return;
        }
        registry.counter(
                        "parkio.municipal.discovery.duplicate_presentation",
                        "source_family_pair", pair,
                        "outcome", outcome,
                        "reason_category", reason,
                        "policy_version", DiscoveryDuplicatePresentationPolicy.POLICY_VERSION)
                .increment(amount);
    }
}
