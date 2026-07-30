package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.externalsource.registry.RegistryField;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class RegistryMetrics {
    private final MeterRegistry registry;

    public RegistryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void candidate(
            String sourceFamilyPair, String outcome, String reasonCategory, String algorithmVersion) {
        registry.counter(
                "parkio.municipal.registry.candidates",
                "source_family_pair", sourceFamilyPair,
                "outcome", outcome,
                "reason_category", reasonCategory,
                "algorithm_version", algorithmVersion).increment();
    }

    public void review(String sourceFamilyPair, String reviewState, String algorithmVersion) {
        registry.counter(
                "parkio.municipal.registry.reviews",
                "source_family_pair", sourceFamilyPair,
                "review_state", reviewState,
                "algorithm_version", algorithmVersion).increment();
    }

    public void provenance(RegistryField field, String outcome) {
        registry.counter(
                "parkio.municipal.registry.provenance",
                "field_name", field.name(),
                "outcome", outcome).increment();
    }
}
