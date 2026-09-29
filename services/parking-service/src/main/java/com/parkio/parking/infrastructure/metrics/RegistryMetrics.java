package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.externalsource.registry.RegistryField;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
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
        provenance(field, outcome, "unknown", "ingest-provenance-v1", "select");
    }

    /**
     * Bounded provenance ingest/reconcile counter (DATA-WP-10/14).
     * Allowed tags only: field_name, outcome, source_family, policy_version, operation (+ application).
     */
    public void provenance(
            RegistryField field,
            String outcome,
            String sourceFamily,
            String policyVersion,
            String operation) {
        registry.counter(
                "parkio.municipal.registry.provenance",
                "field_name", field.name(),
                "outcome", outcome,
                "source_family", sourceFamily == null ? "unknown" : sourceFamily,
                "policy_version", policyVersion == null ? "unknown" : policyVersion,
                "operation", operation == null ? "select" : operation).increment();
    }

    public void generationRun(String sourceFamilyPair, String outcome, boolean dryRun) {
        registry.counter(
                "parkio.municipal.registry.generation.runs",
                "source_family_pair", sourceFamilyPair,
                "outcome", outcome,
                "dry_run", Boolean.toString(dryRun)).increment();
    }

    public void generationDuration(String sourceFamilyPair, boolean dryRun, long durationMs) {
        registry.timer(
                "parkio.municipal.registry.generation.duration",
                "source_family_pair", sourceFamilyPair,
                "dry_run", Boolean.toString(dryRun)).record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void generationPairs(
            String sourceFamilyPair, String algorithmVersion, boolean dryRun, int count) {
        DistributionSummary.builder("parkio.municipal.registry.generation.pairs")
                .tag("source_family_pair", sourceFamilyPair)
                .tag("algorithm_version", algorithmVersion)
                .tag("dry_run", Boolean.toString(dryRun))
                .register(registry)
                .record(count);
    }

    public void generationCount(
            String sourceFamilyPair,
            String outcome,
            String reasonCategory,
            String algorithmVersion,
            boolean dryRun,
            int count) {
        if (count <= 0) return;
        registry.counter(
                "parkio.municipal.registry.generation.outcomes",
                "source_family_pair", sourceFamilyPair,
                "outcome", outcome,
                "reason_category", reasonCategory,
                "algorithm_version", algorithmVersion,
                "dry_run", Boolean.toString(dryRun)).increment(count);
    }
}
