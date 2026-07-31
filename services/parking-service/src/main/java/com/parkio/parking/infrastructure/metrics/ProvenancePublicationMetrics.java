package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.externalsource.registry.PublicProvenancePublicationPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Bounded internal metrics for DATA-WP-09 public provenance publication.
 * Labels never include facility ID, name, address, coordinates, or request ID.
 */
@Component
public class ProvenancePublicationMetrics {
    private final MeterRegistry registry;

    public ProvenancePublicationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordHidden() {
        increment("hidden", "none", 0);
    }

    public void recordEmpty() {
        increment("empty", "none", 0);
    }

    public void recordEnriched(PublicProvenancePublicationPolicy.BoundedProvenance provenance) {
        String family = PublicProvenancePublicationPolicy.sourceFamilyLabel(
                provenance.contributingSourceKeys());
        increment("enriched", family, provenance.selectedFieldProvenanceSummary().size());
        for (String fieldName : provenance.selectedFieldProvenanceSummary().keySet()) {
            registry.counter(
                    "parkio.municipal.registry.provenance.publication.fields",
                    "field_name", fieldName,
                    "policy_version", PublicProvenancePublicationPolicy.POLICY_VERSION)
                    .increment();
        }
    }

    private void increment(String outcome, String sourceFamily, int fieldCount) {
        registry.counter(
                "parkio.municipal.registry.provenance.publication",
                "outcome", outcome,
                "source_family", sourceFamily,
                "policy_version", PublicProvenancePublicationPolicy.POLICY_VERSION)
                .increment();
        if (fieldCount > 0) {
            registry.counter(
                    "parkio.municipal.registry.provenance.publication.field_count",
                    "outcome", outcome,
                    "source_family", sourceFamily,
                    "policy_version", PublicProvenancePublicationPolicy.POLICY_VERSION)
                    .increment(fieldCount);
        }
    }
}
