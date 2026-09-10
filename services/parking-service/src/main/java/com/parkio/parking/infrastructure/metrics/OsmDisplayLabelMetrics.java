package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.externalsource.osm.OsmDisplayLabelOutcome;
import com.parkio.parking.externalsource.osm.OsmDisplayLabelPolicy;
import com.parkio.parking.externalsource.osm.OsmDisplayLabelSelection;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Bounded-label OSM display-label metrics (DATA-WP-13). Never facility/OSM IDs or name text. */
@Component
public class OsmDisplayLabelMetrics {
    private static final String SOURCE_FAMILY = "osm";

    private final MeterRegistry registry;

    public OsmDisplayLabelMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(OsmDisplayLabelSelection selection) {
        if (selection == null) {
            return;
        }
        String policy = OsmDisplayLabelPolicy.normalizePolicyVersion(selection.policyVersion());
        OsmDisplayLabelOutcome outcome = selection.outcome();
        registry.counter(
                        "parkio.municipal.osm.label",
                        "outcome", outcome.metricOutcome(),
                        "source_family", SOURCE_FAMILY,
                        "policy_version", policy,
                        "fallback_type", outcome.fallbackType())
                .increment();
        if (selection.rejectedCandidateCount() > 0) {
            registry.counter(
                            "parkio.municipal.osm.label",
                            "outcome", OsmDisplayLabelOutcome.INVALID_NAME_REJECTED.metricOutcome(),
                            "source_family", SOURCE_FAMILY,
                            "policy_version", policy,
                            "fallback_type", "invalid")
                    .increment(selection.rejectedCandidateCount());
        }
        if (selection.technicalIdRejectedCount() > 0) {
            registry.counter(
                            "parkio.municipal.osm.label",
                            "outcome", OsmDisplayLabelOutcome.TECHNICAL_ID_REMOVED.metricOutcome(),
                            "source_family", SOURCE_FAMILY,
                            "policy_version", policy,
                            "fallback_type", "technical_id")
                    .increment(selection.technicalIdRejectedCount());
        }
    }

    /** Facility row unchanged on reimport (label policy still evaluated; no row mutation). */
    public void recordUnchanged(String policyVersion) {
        String policy = OsmDisplayLabelPolicy.normalizePolicyVersion(policyVersion);
        registry.counter(
                        "parkio.municipal.osm.label",
                        "outcome", OsmDisplayLabelOutcome.UNCHANGED.metricOutcome(),
                        "source_family", SOURCE_FAMILY,
                        "policy_version", policy,
                        "fallback_type", OsmDisplayLabelOutcome.UNCHANGED.fallbackType())
                .increment();
    }
}
